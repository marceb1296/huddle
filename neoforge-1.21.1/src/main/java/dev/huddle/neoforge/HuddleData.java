package dev.huddle.neoforge;

import dev.huddle.core.Cooldowns;
import dev.huddle.core.Mailbox;
import dev.huddle.core.Pot;
import java.util.LinkedHashMap;
import dev.huddle.core.Pos;
import dev.huddle.core.Records;
import dev.huddle.core.Route;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

// datos que sobreviven a un reinicio, guardados con el mundo principal
public final class HuddleData extends SavedData {
    private static final String NAME = "huddle";

    private final Cooldowns cooldowns = new Cooldowns();
    private final Map<String, Tracks.Stored> tracks = new TreeMap<>();
    private final Map<UUID, Participants.Spot> returns = new HashMap<>();
    private final Map<UUID, Pot<BuyIns.Stake>> pots = new HashMap<>();
    private Mailbox<BuyIns.Stake> mailbox = new Mailbox<>();

    public static HuddleData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(HuddleData::new, HuddleData::load), NAME);
    }

    public Cooldowns cooldowns() {
        return cooldowns;
    }

    public Map<String, Tracks.Stored> tracks() {
        return tracks;
    }

    public Map<UUID, Participants.Spot> returns() {
        return returns;
    }

    public Map<UUID, Pot<BuyIns.Stake>> pots() {
        return pots;
    }

    public Mailbox<BuyIns.Stake> mailbox() {
        return mailbox;
    }

    // core no sabe de SavedData: quien cambia los enfriamientos avisa
    public void changed() {
        setDirty();
    }

    static HuddleData load(CompoundTag tag, HolderLookup.Provider registries) {
        HuddleData data = new HuddleData();
        CompoundTag cd = tag.getCompound("cooldowns");
        Map<String, Cooldowns.Entry> saved = new HashMap<>();
        for (String key : cd.getAllKeys()) {
            CompoundTag e = cd.getCompound(key);
            saved.put(key, new Cooldowns.Entry(e.getLong("until"), e.getLong("length")));
        }
        data.cooldowns.restore(saved);
        CompoundTag tr = tag.getCompound("tracks");
        for (String name : tr.getAllKeys()) {
            Optional<Tracks.Stored> t = loadTrack(name, tr.getCompound(name));
            if (t.isPresent()) {
                data.tracks.put(t.get().route().name(), t.get());
            } else {
                Huddle.LOGGER.warn("Huddle: skipped the saved track '{}', its data is broken", name);
            }
        }
        CompoundTag back = tag.getCompound("returns");
        for (String key : back.getAllKeys()) {
            CompoundTag t = back.getCompound(key);
            ResourceLocation dim = ResourceLocation.tryParse(t.getString("dim"));
            net.minecraft.world.phys.Vec3 pos = new net.minecraft.world.phys.Vec3(t.getDouble("x"), t.getDouble("y"), t.getDouble("z"));
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (dim != null && Double.isFinite(pos.x) && Double.isFinite(pos.y) && Double.isFinite(pos.z)) {
                data.returns.put(id, new Participants.Spot(ResourceKey.create(Registries.DIMENSION, dim), pos, t.getFloat("yRot"), t.getFloat("xRot"),
                        t.hasUUID("mount") ? t.getUUID("mount") : null));
            }
        }
        CompoundTag potsTag = tag.getCompound("pots");
        for (String key : potsTag.getAllKeys()) {
            UUID event = uuid(key);
            CompoundTag t = potsTag.getCompound(key);
            Map<UUID, BuyIns.Stake> stakes = new LinkedHashMap<>();
            CompoundTag st = t.getCompound("stakes");
            for (String who : st.getAllKeys()) {
                UUID id = uuid(who);
                BuyIns.Stake stake = stake(st.getCompound(who));
                if (id != null && stake != null) {
                    stakes.put(id, stake);
                }
            }
            List<UUID> forfeited = new ArrayList<>();
            for (Tag f : t.getList("forfeited", Tag.TAG_INT_ARRAY)) {
                forfeited.add(net.minecraft.nbt.NbtUtils.loadUUID(f));
            }
            if (event != null) {
                data.pots.put(event, Pot.restore(stakes, forfeited));
            }
        }
        Map<UUID, List<BuyIns.Stake>> box = new HashMap<>();
        CompoundTag mail = tag.getCompound("mailbox");
        for (String who : mail.getAllKeys()) {
            UUID id = uuid(who);
            List<BuyIns.Stake> items = new ArrayList<>();
            for (Tag e : mail.getList(who, Tag.TAG_COMPOUND)) {
                BuyIns.Stake stake = stake((CompoundTag) e);
                if (stake != null) {
                    items.add(stake);
                }
            }
            if (id != null) {
                box.put(id, items);
            }
        }
        data.mailbox = Mailbox.restore(box);
        return data;
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // una cantidad rota no puede crear ítems de la nada: se descarta
    private static BuyIns.Stake stake(CompoundTag t) {
        String item = t.getString("item");
        int count = t.getInt("count");
        return item.isEmpty() || count <= 0 ? null : new BuyIns.Stake(item, count);
    }

    private static CompoundTag stakeTag(BuyIns.Stake s) {
        CompoundTag t = new CompoundTag();
        t.putString("item", s.item());
        t.putInt("count", s.count());
        return t;
    }

    // datos editados a mano o de una versión vieja: Route.of corrige lo que puede y descarta el resto
    static Optional<Tracks.Stored> loadTrack(String name, CompoundTag t) {
        ResourceLocation dim = ResourceLocation.tryParse(t.getString("dim"));
        Optional<Route.Mode> mode = Route.parseMode(t.getString("mode"));
        if (dim == null || mode.isEmpty()) {
            return Optional.empty();
        }
        List<Route.Checkpoint> cps = new ArrayList<>();
        for (Tag e : t.getList("points", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) e;
            cps.add(new Route.Checkpoint(new Pos(c.getDouble("x"), c.getDouble("y"), c.getDouble("z")), c.getDouble("r")));
        }
        UUID author = t.hasUUID("author") ? t.getUUID("author") : null;
        Pos start = new Pos(t.getDouble("sx"), t.getDouble("sy"), t.getDouble("sz"));
        Optional<Route> route = Route.of(name, author, t.getString("authorName"), mode.get(), t.getInt("laps"), start, cps);
        if (route.isEmpty()) {
            return Optional.empty();
        }
        List<Records.Entry> recs = new ArrayList<>();
        for (Tag e : t.getList("records", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) e;
            if (c.hasUUID("player")) {
                recs.add(new Records.Entry(c.getUUID("player"), c.getString("name"), c.getDouble("ticks")));
            }
        }
        return Optional.of(new Tracks.Stored(route.get(), ResourceKey.create(Registries.DIMENSION, dim), Records.restore(recs)));
    }

    static CompoundTag saveTrack(Tracks.Stored s) {
        Route r = s.route();
        CompoundTag t = new CompoundTag();
        if (r.author() != null) {
            t.putUUID("author", r.author());
        }
        t.putString("authorName", r.authorName());
        t.putString("mode", r.mode().name());
        t.putInt("laps", r.laps());
        t.putString("dim", s.dimension().location().toString());
        t.putDouble("sx", r.start().x());
        t.putDouble("sy", r.start().y());
        t.putDouble("sz", r.start().z());
        ListTag points = new ListTag();
        for (Route.Checkpoint cp : r.checkpoints()) {
            CompoundTag c = new CompoundTag();
            c.putDouble("x", cp.center().x());
            c.putDouble("y", cp.center().y());
            c.putDouble("z", cp.center().z());
            c.putDouble("r", cp.radius());
            points.add(c);
        }
        t.put("points", points);
        ListTag recs = new ListTag();
        for (Records.Entry e : s.records().top()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("player", e.player());
            c.putString("name", e.name());
            c.putDouble("ticks", e.ticks());
            recs.add(c);
        }
        t.put("records", recs);
        return t;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        cooldowns.prune(System.currentTimeMillis());
        CompoundTag cd = new CompoundTag();
        cooldowns.snapshot().forEach((key, e) -> {
            CompoundTag t = new CompoundTag();
            t.putLong("until", e.until());
            t.putLong("length", e.length());
            cd.put(key, t);
        });
        tag.put("cooldowns", cd);
        CompoundTag tr = new CompoundTag();
        tracks.forEach((name, t) -> tr.put(name, saveTrack(t)));
        tag.put("tracks", tr);
        CompoundTag back = new CompoundTag();
        returns.forEach((id, spot) -> {
            CompoundTag t = new CompoundTag();
            t.putString("dim", spot.dimension().location().toString());
            t.putDouble("x", spot.pos().x);
            t.putDouble("y", spot.pos().y);
            t.putDouble("z", spot.pos().z);
            t.putFloat("yRot", spot.yRot());
            t.putFloat("xRot", spot.xRot());
            if (spot.mount() != null) {
                t.putUUID("mount", spot.mount());
            }
            back.put(id.toString(), t);
        });
        tag.put("returns", back);
        CompoundTag potsTag = new CompoundTag();
        pots.forEach((event, pot) -> {
            CompoundTag t = new CompoundTag();
            CompoundTag st = new CompoundTag();
            pot.stakes().forEach((who, stake) -> st.put(who.toString(), stakeTag(stake)));
            t.put("stakes", st);
            ListTag forfeited = new ListTag();
            pot.forfeitedPlayers().forEach(f -> forfeited.add(net.minecraft.nbt.NbtUtils.createUUID(f)));
            t.put("forfeited", forfeited);
            potsTag.put(event.toString(), t);
        });
        tag.put("pots", potsTag);
        CompoundTag mail = new CompoundTag();
        mailbox.snapshot().forEach((who, items) -> {
            ListTag l = new ListTag();
            items.forEach(i -> l.add(stakeTag(i)));
            mail.put(who.toString(), l);
        });
        tag.put("mailbox", mail);
        return tag;
    }
}
