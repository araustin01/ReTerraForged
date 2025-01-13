package raccoonman.reterraforged.forge.tfc;

import com.mojang.serialization.Codec;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import raccoonman.reterraforged.RTFCommon;

public final class TFCChunkGeneratorData {

    public static final DeferredRegister<Codec<? extends ChunkGenerator>> CHUNK_GENERATOR = DeferredRegister.create(Registries.CHUNK_GENERATOR, RTFCommon.MOD_ID);
//    public static final ResourceKey<ChunkGenerator> OVERWORLD = ResourceKey.create(Registries.CHUNK_GENERATOR, new ResourceLocation("overworld"));

    public static void bootstrap() {
        CHUNK_GENERATOR.register("overworld", () -> TFCCompatibleChunkGenerator.CODEC);
    }

}
