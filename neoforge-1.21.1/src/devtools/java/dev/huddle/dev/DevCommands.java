package dev.huddle.dev;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.HashMap;
import java.util.Map;
import dev.huddle.neoforge.Glow;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// solo para el servidor de prueba (tools/test-client.sh): nunca va en el jar publicado
@Mod("huddle_dev")
@EventBusSubscriber(modid = "huddle_dev")
public final class DevCommands {
    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("huddledev").requires(src -> src.hasPermission(2))
                .then(Commands.literal("glow")
                        .then(Commands.argument("viewer", EntityArgument.player())
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .then(Commands.literal("on").executes(ctx -> {
                                            Glow.show(EntityArgument.getPlayer(ctx, "viewer"), EntityArgument.getEntity(ctx, "target"));
                                            return 1;
                                        }))
                                        .then(Commands.literal("off").executes(ctx -> {
                                            Glow.hide(EntityArgument.getPlayer(ctx, "viewer"), EntityArgument.getEntity(ctx, "target"));
                                            return 1;
                                        })))))
                // empuja el vehículo del jugador; marked: como lo haría el servidor con cualquier entidad,
                // packet: además le manda el movimiento directo a quien lo maneja
                // camina un paso por tick sin evento de teletransporte, como un jugador que corre
                .then(Commands.literal("walk")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("dx", DoubleArgumentType.doubleArg(-5, 5))
                                        .then(Commands.argument("dz", DoubleArgumentType.doubleArg(-5, 5))
                                                .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 2000)).executes(ctx -> {
                                                    WALKS.put(EntityArgument.getPlayer(ctx, "player"), new double[] {
                                                            DoubleArgumentType.getDouble(ctx, "dx"), DoubleArgumentType.getDouble(ctx, "dz"),
                                                            IntegerArgumentType.getInteger(ctx, "ticks")});
                                                    return 1;
                                                }))))))
                .then(Commands.literal("push")
                        .then(Commands.argument("rider", EntityArgument.player())
                                .then(Commands.argument("speed", DoubleArgumentType.doubleArg(-5, 5))
                                        .then(Commands.literal("marked").executes(ctx -> push(ctx.getSource()::sendSuccess,
                                                EntityArgument.getPlayer(ctx, "rider"), DoubleArgumentType.getDouble(ctx, "speed"), false)))
                                        .then(Commands.literal("packet").executes(ctx -> push(ctx.getSource()::sendSuccess,
                                                EntityArgument.getPlayer(ctx, "rider"), DoubleArgumentType.getDouble(ctx, "speed"), true)))))));
    }

    private static final Map<ServerPlayer, double[]> WALKS = new HashMap<>();

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        WALKS.entrySet().removeIf(e -> {
            ServerPlayer p = e.getKey();
            double[] w = e.getValue();
            if (p.isRemoved() || w[2]-- <= 0) {
                return true;
            }
            p.teleportTo(p.serverLevel(), p.getX() + w[0], p.getY(), p.getZ() + w[1], p.getYRot(), p.getXRot());
            return false;
        });
    }

    private interface Reply {
        void send(java.util.function.Supplier<Component> message, boolean broadcast);
    }

    private static int push(Reply reply, ServerPlayer rider, double speed, boolean packet) {
        Entity vehicle = rider.getVehicle();
        if (vehicle == null) {
            reply.send(() -> Component.literal("no vehicle"), false);
            return 0;
        }
        Vec3 v = new Vec3(0, 0, speed);
        vehicle.setDeltaMovement(v);
        vehicle.hurtMarked = true;
        if (packet) {
            rider.connection.send(new ClientboundSetEntityMotionPacket(vehicle.getId(), v));
        }
        reply.send(() -> Component.literal("pushed " + vehicle.getType() + " at " + vehicle.position()), false);
        return 1;
    }
}
