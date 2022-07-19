package cluster;

import java.time.Duration;
import java.util.Date;

import org.slf4j.Logger;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.TimerScheduler;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import cluster.BankEntityActor.Command;

class BankEntityCommandActor extends AbstractBehavior<BankEntityActor.Command> {
    private final ActorContext<BankEntityActor.Command> actorContext;
    private final ClusterSharding clusterSharding;
    private final int entitiesPerNode;
    private final Integer nodePort;
    static Behavior<BankEntityActor.Command> create() {
        return Behaviors.setup(actorContext ->
                Behaviors.withTimers(timer -> new BankEntityCommandActor(actorContext, timer)));
    }

    private BankEntityCommandActor(ActorContext<Command> actorContext, TimerScheduler<BankEntityActor.Command> timerScheduler) {
        super(actorContext);
        this.actorContext = actorContext;
        clusterSharding = ClusterSharding.get(actorContext.getSystem());

        entitiesPerNode = actorContext.getSystem().settings().config().getInt("entity-actor.entities-per-node");
        final var interval = Duration.parse(actorContext.getSystem().settings().config().getString("entity-actor.command-tick-interval-iso-8601"));
        timerScheduler.startTimerWithFixedDelay(Tick.ticktock, interval);
        nodePort = actorContext.getSystem().address().getPort().orElse(-1);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Tick.class, t -> onTick())
                .onMessage(BankEntityActor.ChangeValueAck.class, this::onChangeValueAck)
                .build();
    }

    private Behavior<BankEntityActor.Command> onTick() {
        final var entityId = BankEntityActor.entityId(nodePort, (int) Math.round(Math.random() * entitiesPerNode));
        final var id = new BankEntityActor.Id(entityId);
        final var value = new BankEntityActor.Value(new Date());
        final var amount = new Integer(0);
        /*The entityRef is the reference for that entity calculate with cluster sharding*/
        final var entityRef = clusterSharding.entityRefFor(BankEntityActor.entityTypeKey, entityId);
        /*Giving the return address with getSelf*/
        entityRef.tell(new BankEntityActor.ChangeValue(id, value,amount, actorContext.getSelf()));

        return this;
    }

    private Behavior<BankEntityActor.Command> onChangeValueAck(BankEntityActor.ChangeValueAck changeValueAck) { /* Comment Flag: This logs when the command to change the entity value occurs */
        log().info("onChangeValue: {}", changeValueAck);
        return this;
    }

    private Logger log() {
        return actorContext.getSystem().log();
    }

    enum Tick implements BankEntityActor.Command {
        ticktock
    }
}