package raccoonman.reterraforged.forge.tfc.biome;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryFileCodec;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.*;
import net.minecraftforge.registries.DeferredRegister;
import raccoonman.reterraforged.RTFCommon;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TFCBiomeSourceParameterList extends MultiNoiseBiomeSourceParameterList {
    public static final DeferredRegister<? extends MultiNoiseBiomeSourceParameterList> BIOME_SOURCE_PARAMETER_LIST_REGISTRY = DeferredRegister.create(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST, RTFCommon.MOD_ID);
    public static final Codec<TFCBiomeSourceParameterList> DIRECT_CODEC = RecordCodecBuilder.create((instance) -> {
        return instance.group(TFCBiomeSourceParameterList.Preset.CODEC.fieldOf("preset").forGetter((c) -> {
            return c.preset;
        }), RegistryOps.retrieveGetter(Registries.BIOME)).apply(instance, TFCBiomeSourceParameterList::new);
    });
    public static final Codec<Holder<TFCBiomeSourceParameterList>> CODEC;
    private final TFCBiomeSourceParameterList.Preset preset;
    private final Climate.ParameterList<Holder<Biome>> parameters;

    public TFCBiomeSourceParameterList(TFCBiomeSourceParameterList.Preset preset, HolderGetter<Biome> holderGetter) {
        super(null, holderGetter);
        RTFCommon.LOGGER.info("Initialized TFCBiomeSourceParameterList!");
        this.preset = preset;
        TFCBiomeSourceParameterList.Preset.SourceProvider var10001 = preset.provider;
        Objects.requireNonNull(holderGetter);
        this.parameters = var10001.apply(holderGetter::getOrThrow);
    }

    public Climate.ParameterList<Holder<Biome>> parameters() {
        return this.parameters;
    }

    public static Map<MultiNoiseBiomeSourceParameterList.Preset, Climate.ParameterList<ResourceKey<Biome>>> knownPresets() {
        return (Map)TFCBiomeSourceParameterList.Preset.BY_NAME.values().stream().collect(Collectors.toMap((preset) -> {
            return preset;
        }, (preset) -> {
            return preset.provider().apply((resourceKey) -> {
                return resourceKey;
            });
        }));
    }

    static {
        RTFCommon.LOGGER.info("TFCBiomeSourceParameterList registering type=" + BIOME_SOURCE_PARAMETER_LIST_REGISTRY.getRegistryName());
        CODEC = RegistryFileCodec.create(BIOME_SOURCE_PARAMETER_LIST_REGISTRY.getRegistryKey(), (Codec) DIRECT_CODEC);
        RTFCommon.LOGGER.info("DeferredRegister registered codec=" + CODEC.getClass().getName());

        //BIOME_SOURCE_PARAMETER_LIST_REGISTRY.register("worldgen/multi_noise_biome_source_parameter_list", () -> DIRECT_CODEC);
    }

    public record Preset(ResourceLocation id, SourceProvider provider) {

        public static final TFCBiomeSourceParameterList.Preset NETHER = new TFCBiomeSourceParameterList.Preset(new ResourceLocation("nether"), new TFCBiomeSourceParameterList.Preset.SourceProvider() {
            public <T> Climate.ParameterList<T> apply(Function<ResourceKey<Biome>, T> function) {
                return new Climate.ParameterList(List.of(Pair.of(Climate.parameters(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F), function.apply(Biomes.NETHER_WASTES)), Pair.of(Climate.parameters(0.0F, -0.5F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F), function.apply(Biomes.SOUL_SAND_VALLEY)), Pair.of(Climate.parameters(0.4F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F), function.apply(Biomes.CRIMSON_FOREST)), Pair.of(Climate.parameters(0.0F, 0.5F, 0.0F, 0.0F, 0.0F, 0.0F, 0.375F), function.apply(Biomes.WARPED_FOREST)), Pair.of(Climate.parameters(-0.5F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.175F), function.apply(Biomes.BASALT_DELTAS))));
            }
        });
        public static final TFCBiomeSourceParameterList.Preset OVERWORLD = new TFCBiomeSourceParameterList.Preset(new ResourceLocation("overworld"), new TFCBiomeSourceParameterList.Preset.SourceProvider() {
            public <T> Climate.ParameterList<T> apply(Function<ResourceKey<Biome>, T> function) {
                return Preset.generateOverworldBiomes(function);
            }
        });
        static final Map<ResourceLocation, TFCBiomeSourceParameterList.Preset> BY_NAME;
        public static final Codec<TFCBiomeSourceParameterList.Preset> CODEC;


        public Preset(ResourceLocation id, SourceProvider provider) {
            this.id = id;
            this.provider = provider;
        }
        static <T> Climate.ParameterList<T> generateOverworldBiomes(Function<ResourceKey<Biome>, T> function) {
            ImmutableList.Builder<Pair<Climate.ParameterPoint, T>> builder = ImmutableList.builder();
            (new TFCOverworldBiomeBuilder()).addBiomes((pair) -> {
                builder.add(pair.mapSecond(function));
            });
            return new Climate.ParameterList(builder.build());
        }

        public Stream<ResourceKey<Biome>> usedBiomes() {
            return this.provider.apply((resourceKey) -> {
                return resourceKey;
            }).values().stream().map(Pair::getSecond).distinct();
        }

        public ResourceLocation id() {
            return this.id;
        }

        public SourceProvider provider() {
            return this.provider;
        }

        static {
            BY_NAME = (Map)Stream.of(NETHER, OVERWORLD).collect(Collectors.toMap(Preset::id, (preset) -> {
                return preset;
            }));
            CODEC = ResourceLocation.CODEC.flatXmap((resourceLocation) -> {
                return (DataResult) Optional.ofNullable((Preset) BY_NAME.get(resourceLocation))
                        .map(DataResult::success)
                        .orElseGet(() -> DataResult.error(() -> "Unknown preset: " + resourceLocation));
            }, (preset) -> {
                return DataResult.success(((Preset) preset).id);
            });
        }

        @FunctionalInterface
        interface SourceProvider {
            <T> Climate.ParameterList<T> apply(Function<ResourceKey<Biome>, T> function);
        }

    }
}
