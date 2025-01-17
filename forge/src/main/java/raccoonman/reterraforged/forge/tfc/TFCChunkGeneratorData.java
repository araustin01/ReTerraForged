package raccoonman.reterraforged.forge.tfc;

import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraftforge.registries.DeferredRegister;
import raccoonman.reterraforged.RTFCommon;

import org.apache.logging.log4j.Level;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.forge.RTFForge;
import raccoonman.reterraforged.registries.RTFRegistries;


public final class TFCChunkGeneratorData {

    public static final DeferredRegister<Codec<? extends ChunkGenerator>> CHUNK_GENERATOR = DeferredRegister.create(Registries.CHUNK_GENERATOR, RTFCommon.MOD_ID);
    public static final ResourceKey<TFCCompatibleChunkGenerator> OVERWORLD = ResourceKey.create(RTFForge.CHUNK_GENERATOR, RTFCommon.location("overworld"));

    public static TFCCompatibleChunkGenerator INSTANCE;

    public static void bootstrap(Preset preset, BootstapContext<TFCCompatibleChunkGenerator> ctx) {
       // CHUNK_GENERATOR.register("overworld", () -> TFCCompatibleChunkGenerator.CODEC);
          ctx.register(OVERWORLD, INSTANCE);
//        RTFCommon.LOGGER.info("TFC CHUNK_GENERATOR Registered [INSTANCE=" + (INSTANCE == null ? "NULL" : INSTANCE.hashCode()) + "]!");
    }

}
