package cluster;

import java.time.Duration;

import org.slf4j.Logger;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.TimerScheduler;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import cluster.BankEntityActor.Command;

class BankEntityQueryActor extends AbstractBehavior<BankEntityActor.Command> {
    private final ActorContext<BankEntityActor.Command> actorContext;
    private final ClusterSharding clusterSharding;
    private final int entitiesPerNode;
    private final Integer nodePort;

    static Behavior<BankEntityActor.Command> create() {
        return Behaviors.setup(actorContext ->
                Behaviors.withTimers(timer -> new BankEntityQueryActor(actorContext, timer)));
    }

    private BankEntityQueryActor(ActorContext<Command> actorContext, TimerScheduler<BankEntityActor.Command> timerScheduler) {
        super(actorContext);
        this.actorContext = actorContext;
        clusterSharding = ClusterSharding.get(actorContext.getSystem());

        entitiesPerNode = actorContext.getSystem().settings().config().getInt("entity-actor.entities-per-node");
        final var interval = Duration.parse(actorContext.getSystem().settings().config().getString("entity-actor.query-tick-interval-iso-8601"));
        timerScheduler.startTimerWithFixedDelay(Tick.ticktock, interval);
        nodePort = actorContext.getSystem().address().getPort().orElse(-1);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Tick.class, t -> onTick())
                .onMessage(BankEntityActor.GetValueAck.class, this::onGetValueAck)
                .onMessage(BankEntityActor.GetValueAckNotFound.class, this::onGetValueAckNotFound)
                .build();
    }

    private Behavior<BankEntityActor.Command> onTick() {
        final var entityId = EntityActor.entityId(nodePort, (int) Math.round(Math.random() * entitiesPerNode));
        final var id = new BankEntityActor.Id(entityId);
        final var entityRef = clusterSharding.entityRefFor(BankEntityActor.entityTypeKey, entityId);
        entityRef.tell(new BankEntityActor.GetValue(id, actorContext.getSelf()));
        return this;
    }

    private Behavior<BankEntityActor.Command> onGetValueAck(BankEntityActor.GetValueAck getValueAck) {
        log().info("{}", getValueAck);
        return this;
    }

    private Behavior<BankEntityActor.Command> onGetValueAckNotFound(BankEntityActor.GetValueAckNotFound getValueAckNotFound) {
        log().info("{}", getValueAckNotFound);
        return this;
    }

    private Logger log() {
        return actorContext.getSystem().log();
    }

    enum Tick implements BankEntityActor.Command {
        ticktock
    }
}

