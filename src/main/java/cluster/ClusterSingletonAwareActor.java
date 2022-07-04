package cluster;

import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;

import org.slf4j.Logger;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.TimerScheduler;
import akka.cluster.Cluster;
import akka.cluster.MemberStatus;
import akka.cluster.typed.ClusterSingleton;
import akka.cluster.typed.SingletonActor;

class ClusterSingletonAwareActor extends AbstractBehavior<ClusterSingletonAwareActor.Message> {
  private final ActorRef<Message> clusterSingletonProxy;
  private final ActorRef<HttpServer.Statistics> httpServerActor;
  private final Duration tickInterval = Duration.ofMillis(25 + Math.round(100)); // avg 100ms per tick
  private final int port;

  static Behavior<Message> create(ActorRef<HttpServer.Statistics> httpServerActor) {
    return Behaviors.setup(actorContext ->
        Behaviors.withTimers(timer -> new ClusterSingletonAwareActor(actorContext, timer, httpServerActor)));
  }

  ClusterSingletonAwareActor(ActorContext<Message> actorContext, TimerScheduler<Message> timers, ActorRef<HttpServer.Statistics> httpServerActor) {
    super(actorContext);
    this.httpServerActor = httpServerActor;
    final var selfAddress = Cluster.get(actorContext.getSystem()).selfAddress();
    port = selfAddress.getPort().orElse(-1);
    clusterSingletonProxy = ClusterSingleton.get(actorContext.getSystem())
        .init(SingletonActor.of(ClusterSingletonActor.create(), ClusterSingletonActor.class.getSimpleName()));
    timers.startTimerAtFixedRate(Tick.Instance, tickInterval);
  }

  @Override
  public Receive<Message> createReceive() {
    return newReceiveBuilder()
        .onMessage(Tick.class, notUsed -> onTick())
        .onMessage(Pong.class, this::onPong)
        .build();
  }

  private Behavior<Message> onTick() {
    if (iAmUp()) {
      clusterSingletonProxy.tell(new Ping(getContext().getSelf(), port, System.nanoTime()));
    }
    return Behaviors.same();
  }

  private Behavior<Message> onPong(Pong pong) {
    if (pong.totalPings % 100 == 0) {
      log().info("<--{}", pong);
    }
    httpServerActor.tell(new HttpServer.SingletonAwareStatistics(pong.replyFrom, pong.totalPings, pong.pingRatePs, pong.singletonStatistics));
    return Behaviors.same();
  }

  private boolean iAmUp() {
    return Cluster.get(getContext().getSystem()).selfMember().status().equals(MemberStatus.up());
  }

  interface Message extends CborSerializable { }

  public static class Ping implements Message {
    public final ActorRef<Message> replyTo;
    public final int port;
    public final long start;

    @JsonCreator
    public Ping(ActorRef<Message> replyTo, int port, long start) {
      this.replyTo = replyTo;
      this.port = port;
      this.start = start;
    }

    @Override
    public String toString() {
      return String.format("%s[%d, %s]", getClass().getSimpleName(), port, replyTo.path());
    }
  }

  public static class Pong implements Message {
    public final String replyFrom;
    public final long pingStart;
    public final int totalPings;
    public final int pingRatePs;
    public final Map<Integer, Integer> singletonStatistics;

    @JsonCreator
    public Pong(String replyFrom, long pingStart, int totalPings, int pingRatePs, Map<Integer, Integer> singletonStatistics) {
      this.replyFrom = replyFrom;
      this.pingStart = pingStart;
      this.totalPings = totalPings;
      this.pingRatePs = pingRatePs;
      this.singletonStatistics = singletonStatistics;
    }

    @Override
    public String toString() {
      return String.format("%s[%s, %,dns, %s]", getClass().getSimpleName(), replyFrom, System.nanoTime() - pingStart, singletonStatistics);
    }
  }

  enum Tick implements Message {
    Instance
  }

  private Logger log() {
    return getContext().getLog();
  }
}
