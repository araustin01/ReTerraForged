package raccoonman.reterraforged.forge.tfc;

import com.mojang.datafixers.Products;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.dries007.tfc.world.biome.BiomeSourceExtension;
import net.dries007.tfc.world.settings.Settings;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import raccoonman.reterraforged.RTFCommon;

import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;

import java.util.Optional;
import java.util.function.Consumer;


public final class TFCChunkGeneratorData {

    public static final DeferredRegister<Codec<? extends ChunkGenerator>> CHUNK_GENERATOR_REGISTRY = DeferredRegister.create(Registries.CHUNK_GENERATOR, RTFCommon.MOD_ID);

    public static RegistryObject<Codec<TFCCompatibleChunkGenerator>> CHUNK_GENERATOR;

    static {
        RTFCommon.LOGGER.info("TFCChunkGeneratorData Bootstrap registering type=" + CHUNK_GENERATOR_REGISTRY.getRegistryName());
        CHUNK_GENERATOR = CHUNK_GENERATOR_REGISTRY.register("overworld", () -> TFCCompatibleChunkGenerator.CODEC);
        RTFCommon.LOGGER.info("DeferredRegister registered key=" + CHUNK_GENERATOR.getKey());
    }

}
