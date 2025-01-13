package raccoonman.reterraforged.data.worldgen.tfc;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstapContext;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;

public final class TFCChunkGeneratorData {

//    public static final DeferredRegister<Codec<? extends ChunkGenerator>> CHUNK_GENERATOR = DeferredRegister.create(Registries.CHUNK_GENERATOR, RTFCommon.MOD_ID);
    public static final ResourceKey<Codec<? extends ChunkGenerator>> OVERWORLD = ResourceKey.create(Registries.CHUNK_GENERATOR, new ResourceLocation("overworld"));

    public static void bootstrap(Preset preset, BootstapContext<Codec<? extends ChunkGenerator>> ctx) {
        ResourceLocation key = new ResourceLocation(RTFCommon.MOD_ID, "overworld");

        HolderGetter<Biome> biomes = ctx.lookup(Registries.BIOME);
//        RegionBiomeSource source = new RegionBiomeSource(biomes);



//        ctx.register(OVERWORLD, new TFCCompatibleChunkGenerator(source, null, new Settings(false, 4000, 0, 0, new RockLayerSettings.Data())));

        // CHUNK_GENERATOR.register("overworld", () -> TFCChunkGenerator.CODEC);
    }

}
