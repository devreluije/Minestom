package net.minestom.server.entity.metadata.monster.zombie;

import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Metadata;
import net.minestom.server.entity.metadata.villager.VillagerMeta;
import org.jetbrains.annotations.NotNull;

public class ZombieVillagerMeta extends ZombieMeta {

    public ZombieVillagerMeta(@NotNull Entity entity) {
        super(entity);
    }

    public boolean isConverting() {
        return getMetadata().getIndex((byte) 18, false);
    }

    public void setConverting(boolean value) {
        getMetadata().setIndex((byte) 18, Metadata.Boolean(value));
    }

    public VillagerMeta.VillagerData getVillagerData() {
        int[] data = getMetadata().getIndex((byte) 17, null);
        if (data == null) {
            return new VillagerMeta.VillagerData(VillagerMeta.Type.PLAINS, VillagerMeta.Profession.NONE, VillagerMeta.Level.NOVICE);
        }
        return new VillagerMeta.VillagerData(VillagerMeta.Type.VALUES[data[0]], VillagerMeta.Profession.VALUES[data[1]], VillagerMeta.Level.VALUES[data[2] - 1]);
    }

    public void setVillagerData(VillagerMeta.VillagerData data) {
        getMetadata().setIndex((byte) 17, Metadata.VillagerData(
                data.getType().ordinal(),
                data.getProfession().ordinal(),
                data.getLevel().ordinal() + 1
        ));
    }

}
