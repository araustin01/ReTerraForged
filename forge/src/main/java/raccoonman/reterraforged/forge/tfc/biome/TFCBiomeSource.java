package raccoonman.reterraforged.forge.tfc.biome;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Lifecycle;
import com.mojang.serialization.MapCodec;
import net.dries007.tfc.world.biome.BiomeExtension;
import net.dries007.tfc.world.biome.BiomeSourceExtension;
import net.dries007.tfc.world.layer.framework.ConcurrentArea;
import net.dries007.tfc.world.region.RegionGenerator;
import net.dries007.tfc.world.region.RegionPartition;
import net.dries007.tfc.world.region.Units;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.world.level.biome.*;
import net.minecraftforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;
import raccoonman.reterraforged.RTFCommon;

import java.util.stream.Stream;

public class TFCBiomeSource extends BiomeSource implements BiomeSourceExtension {

    public static final DeferredRegister<Codec<? extends BiomeSource>> BIOME_SOURCE = DeferredRegister.create(Registries.BIOME_SOURCE, RTFCommon.MOD_ID);
    private static final MapCodec<Holder<Biome>> ENTRY_CODEC;
    public static final MapCodec<Climate.ParameterList<Holder<Biome>>> DIRECT_CODEC;
    private static final MapCodec<Holder<MultiNoiseBiomeSourceParameterList>> PRESET_CODEC;
    public static final Codec<TFCBiomeSource> CODEC;

    static
    {
        ENTRY_CODEC = Biome.CODEC.fieldOf("biome");
        DIRECT_CODEC = Climate.ParameterList.codec(ENTRY_CODEC).fieldOf("biomes");
        PRESET_CODEC = MultiNoiseBiomeSourceParameterList.CODEC.fieldOf("preset").withLifecycle(Lifecycle.stable());
        CODEC = Codec.mapEither(DIRECT_CODEC, PRESET_CODEC).xmap(TFCBiomeSource::new, (arg) -> {
            return arg.parameters;
        }).codec();
        BIOME_SOURCE.register("overworld", () -> CODEC);
    }

//    private final HolderGetter<Biome> biomeRegistry;
    private RegionGenerator regionGenerator;
    private ConcurrentArea<BiomeExtension> biomeLayer;

    private final Either<Climate.ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>> parameters;

    public TFCBiomeSource(Either<Climate.ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>> either)
    {
        RTFCommon.LOGGER.info("Initializing TFCBiomeSource!");
//        this.biomeRegistry = biomeRegistry;
        this.parameters = either;
    }

    @Override
    public BiomeExtension getBiomeExtensionNoRiver(int quartX, int quartZ)
    {
        return biomeLayer.get(quartX, quartZ);
    }

    @Override
    public Holder<Biome> getBiomeFromExtension(BiomeExtension extension)
    {
//        return biomeRegistry.getOrThrow(extension.key());
        return null;
    }

    public RegionPartition.Point getPartition(int blockX, int blockZ)
    {
        return regionGenerator.getOrCreatePartitionPoint(Units.blockToGrid(blockX), Units.blockToGrid(blockZ));
    }

    @Override
    public void initRandomState(RegionGenerator regionGenerator, ConcurrentArea<BiomeExtension> biomeLayer)
    {
        this.regionGenerator = regionGenerator;
        this.biomeLayer = biomeLayer;
    }

    @Override
    public BiomeSourceExtension copy()
    {
        return new TFCBiomeSource(parameters);
    }

    @Override
    protected Codec<? extends BiomeSource> codec()
    {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes()
    {
//        return TFCBiomes.getAllKeys().stream().map(biomeRegistry::getOrThrow);
        return this.parameters().values().stream().map(Pair::getSecond);
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, @Nullable Climate.Sampler sampler)
    {
        return this.getNoiseBiome(sampler.sample(quartX, quartY, quartZ));
    }

    @VisibleForDebug
    public Holder<Biome> getNoiseBiome(Climate.TargetPoint arg) {
        return (Holder)this.parameters().findValue(arg);
    }

    private Climate.ParameterList<Holder<Biome>> parameters() {
        return (Climate.ParameterList)this.parameters.map((arg) -> {
            return arg;
        }, (arg) -> {
            return ((MultiNoiseBiomeSourceParameterList)arg.value()).parameters();
        });
    }

}
