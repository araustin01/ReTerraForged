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

    public static Holder<NoiseGeneratorSettings> CUSTOM_NOISE_SETTINGS;
    public static Codec<TFCCompatibleChunkGenerator> CHUNK_GENERATOR_CODEC = RecordCodecBuilder.create(instance -> {
        Products.P3<RecordCodecBuilder.Mu<TFCCompatibleChunkGenerator>, BiomeSourceExtension, Holder<NoiseGeneratorSettings>, Settings> group = instance.group(
                BiomeSource.CODEC.comapFlatMap(TFCCompatibleChunkGenerator::guardBiomeSource, BiomeSourceExtension::self).fieldOf("biome_source").forGetter(c -> c.customBiomeSource),
                NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(c -> c.noiseSettings),
                Settings.CODEC.fieldOf("tfc_settings").forGetter(c -> c.settings)
        );
        return group.apply(instance, ((biomeSourceExtension, noiseGeneratorSettingsHolder, settings) -> {
            if(CUSTOM_NOISE_SETTINGS != null) {
                RTFCommon.LOGGER.info("Success!! Using custom NoiseGeneratorSettings: [" + CUSTOM_NOISE_SETTINGS + "]");
            }
            return new TFCCompatibleChunkGenerator(biomeSourceExtension, CUSTOM_NOISE_SETTINGS != null ? CUSTOM_NOISE_SETTINGS : noiseGeneratorSettingsHolder, settings);
        }));
    });

    public static RegistryObject<Codec<TFCCompatibleChunkGenerator>> CHUNK_GENERATOR;

    static {
        RTFCommon.LOGGER.info("TFCChunkGeneratorData Bootstrap registering type=" + CHUNK_GENERATOR_REGISTRY.getRegistryName());
        CHUNK_GENERATOR = CHUNK_GENERATOR_REGISTRY.register("overworld", () -> CHUNK_GENERATOR_CODEC);
        RTFCommon.LOGGER.info("DeferredRegister registered key=" + CHUNK_GENERATOR.getKey());
    }

    public static void bootstrap(Preset preset, BootstapContext<ChunkGenerator> ctx) {
        Optional<Holder.Reference<NoiseGeneratorSettings>> optional = ctx.lookup(Registries.NOISE_SETTINGS).get(NoiseGeneratorSettings.OVERWORLD);
        if (optional.isPresent()){
            CUSTOM_NOISE_SETTINGS = optional.get();
            RTFCommon.LOGGER.info("TFCChunkGeneratorData Bootstrap successfully recovered NoiseGeneratorSettings!");
        } else {
            RTFCommon.LOGGER.info("TFCChunkGeneratorData Bootstrap failed to recover NoiseGeneratorSettings!");
        }
    }

}
