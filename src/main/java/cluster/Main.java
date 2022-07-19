package cluster;

import java.util.Arrays;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Terminated;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.management.javadsl.AkkaManagement;

class Main {
  static Behavior<Void> create() {
    return Behaviors.setup(context -> {
      bootstrap(context);

      return Behaviors.receive(Void.class)
        .onSignal(Terminated.class, signal -> Behaviors.stopped())
        .build();
    });
  }

  private static void bootstrap(final ActorContext<Void> context) {
    context.spawn(ClusterListenerActor.create(), "clusterListener");
    // Comment Flag: An Actor Reference is like url that allows the cluster to communicate with the actor
    final var httpServerActorRef = context.spawn(HttpServerActor.create(), HttpServerActor.class.getSimpleName());

    context.spawn(ClusterAwareActor.create(httpServerActorRef), ClusterAwareActor.class.getSimpleName());
    context.spawn(ClusterSingletonAwareActor.create(httpServerActorRef), ClusterSingletonAwareActor.class.getSimpleName());
    context.spawn(BankEntityCommandActor.create(), BankEntityCommandActor.class.getSimpleName());
    context.spawn(BankEntityQueryActor.create(), BankEntityQueryActor.class.getSimpleName());

    startClusterSharding(context.getSystem(), httpServerActorRef);
  }

  public static void main(String[] args) {
    if (args.length == 0) {
      throw new RuntimeException("Akka node port is required.");
    }
    final var port = Arrays.asList(args).get(0);
    final var actorSystem = ActorSystem.create(Main.create(), "cluster", setupClusterNodeConfig(port));
    AkkaManagement.get(actorSystem).start();
  }

  private static Config setupClusterNodeConfig(String port) {
    final var config = ConfigFactory.load();
    final var useLocalhost2 = config.getBoolean("useLocalhost2");

    final var localhost1 = "127.0.0.1";
    final var localhost2 = "127.0.0.2";
    final var hostname = useLocalhost2 && port.compareTo("2555") > 0 ? localhost2 : localhost1;
    /* Comment Flag: Opens TCP port */
    return ConfigFactory
        .parseString(String.format("akka.remote.artery.canonical.hostname = \"%s\"%n", hostname)
            + String.format("akka.remote.artery.canonical.port=%s%n", port)
            + String.format("akka.management.http.hostname = \"%s\"%n", "127.0.0.1")
            + String.format("akka.management.http.port=%s%n", port.replace("255", "855"))
            + String.format("akka.management.http.route-providers-read-only = %s%n", "false")
            + String.format("akka.remote.artery.advanced.tcp.outbound-client-hostname = %s%n", hostname))
        .withFallback(config);
  }

  private static void startClusterSharding(final ActorSystem<?> actorSystem, ActorRef<HttpServer.Statistics> httpServerActorRef) {
    final var clusterSharding = ClusterSharding.get(actorSystem);
    clusterSharding.init(
      Entity.of(
        BankEntityActor.entityTypeKey,
        entityContext ->
          BankEntityActor.create(entityContext.getEntityId(), httpServerActorRef)
      )
      .withStopMessage(BankEntityActor.Passivate.INSTANCE)
    );
  }
}
