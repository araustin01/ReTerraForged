package raccoonman.reterraforged.forge.tfc;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.mojang.datafixers.Products;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.dries007.tfc.mixin.accessor.ChunkAccessAccessor;
import net.dries007.tfc.mixin.accessor.ChunkGeneratorAccessor;
import net.dries007.tfc.mixin.accessor.ChunkMapAccessor;
import net.dries007.tfc.util.Helpers;
import net.dries007.tfc.world.*;
import net.dries007.tfc.world.biome.BiomeExtension;
import net.dries007.tfc.world.biome.BiomeSourceExtension;
import net.dries007.tfc.world.biome.TFCBiomes;
import net.dries007.tfc.world.chunkdata.*;
import net.dries007.tfc.world.layer.TFCLayers;
import net.dries007.tfc.world.layer.framework.AreaFactory;
import net.dries007.tfc.world.layer.framework.ConcurrentArea;
import net.dries007.tfc.world.noise.ChunkNoiseSamplingSettings;
import net.dries007.tfc.world.noise.Noise2D;
import net.dries007.tfc.world.noise.NoiseSampler;
import net.dries007.tfc.world.noise.OpenSimplex2D;
import net.dries007.tfc.world.region.RegionGenerator;
import net.dries007.tfc.world.river.RiverBlendType;
import net.dries007.tfc.world.river.RiverNoiseSampler;
import net.dries007.tfc.world.settings.Settings;
import net.dries007.tfc.world.surface.SurfaceManager;
import net.minecraft.Util;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jetbrains.annotations.Nullable;
import raccoonman.reterraforged.RTFCommon;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class TFCCompatibleChunkGenerator extends NoiseBasedChunkGenerator implements ChunkGeneratorExtension {

    public static Codec<TFCCompatibleChunkGenerator> CODEC = RecordCodecBuilder.create(instance -> {
        Products.P3<RecordCodecBuilder.Mu<TFCCompatibleChunkGenerator>, BiomeSourceExtension, Holder<NoiseGeneratorSettings>, Settings> group = instance.group(
                BiomeSource.CODEC.comapFlatMap(TFCCompatibleChunkGenerator::guardBiomeSource, BiomeSourceExtension::self).fieldOf("biome_source").forGetter(c -> c.customBiomeSource),
                NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(c -> c.noiseSettings),
                Settings.CODEC.fieldOf("tfc_settings").forGetter(c -> c.settings)
        );
        return group.apply(instance, instance.stable(TFCCompatibleChunkGenerator::new));
    });

    public static final int DECORATION_STEPS = GenerationStep.Decoration.values().length;

    public static DataResult<BiomeSourceExtension> guardBiomeSource(BiomeSource source) {
        RTFCommon.LOGGER.info("TFCCompatibleChunkGenerator#guardBiomeSource");
        return source instanceof BiomeSourceExtension s ? DataResult.success(s) : DataResult.error(() -> "Must be a " + BiomeSourceExtension.class.getSimpleName());
    }


    // Properties set from codec
    public BiomeSourceExtension customBiomeSource; // narrowed type from superclass
    public Holder<NoiseGeneratorSettings> noiseSettings; // Supplier is resolved in constructor
    public Settings settings;

    private final FastConcurrentCache<TFCAquifer> aquiferCache;

    private ChunkDataProvider chunkDataProvider;
    private long noiseSamplerSeed;
    private SurfaceManager surfaceManager;
    private NoiseSampler noiseSampler;

    public TFCCompatibleChunkGenerator(BiomeSourceExtension biomeSource, Holder<NoiseGeneratorSettings> noiseSettings, Settings settings) {
        super(biomeSource.self(), noiseSettings);

        this.noiseSettings = noiseSettings;
        this.customBiomeSource = biomeSource;
        this.settings = settings;

        this.aquiferCache = new FastConcurrentCache<>(256);

        RTFCommon.LOGGER.info("Initialized TFCCompatibleChunkGenerator:");
        RTFCommon.LOGGER.info("> Custom Biomes:");
        for (Holder<Biome> biome : this.customBiomeSource.self().possibleBiomes()) {
            Optional<ResourceKey<Biome>> optional = biome.unwrapKey();
            optional.ifPresent(resourceKey -> RTFCommon.LOGGER.info("\t -" + resourceKey));
        }

        RTFCommon.LOGGER.info("> Noise Settings: ");
        NoiseGeneratorSettings n_settings = noiseSettings.get();
        RTFCommon.LOGGER.info("\t" + n_settings);

        RTFCommon.LOGGER.info("> TFC Settings: ");
        RTFCommon.LOGGER.info(settings.toString());
    }

    @Override
    public Settings settings() {
        return settings;
    }

    @Override
    public void applySettings(UnaryOperator<Settings> settings) {
        this.settings = settings.apply(this.settings);
    }

    @Override
    public ChunkDataProvider chunkDataProvider() {
        return chunkDataProvider;
    }

    @Override
    public Aquifer getOrCreateAquifer(ChunkAccess chunk) {
        final ChunkNoiseSamplingSettings settings = createNoiseSamplingSettingsForChunk(chunk);
        final ChunkBaseBlockSource baseBlockSource = createBaseBlockSourceForChunk(chunk);
        return getOrCreateAquifer(chunk, settings, baseBlockSource);
    }

    @Override
    @SuppressWarnings("ConstantConditions") // this.chunkDataProvider is null
    public void initRandomState(ChunkMap chunkMap, ServerLevel level) {
        if (chunkDataProvider != null) {
            // Already initialized, so (1) duplicate the chunk generator, only for this chunk map, then (2) re-initialize random state
            final TFCChunkGenerator copy = copy();

            ((ChunkMapAccessor) chunkMap).accessor$setGenerator(copy);
            copy.initRandomState(chunkMap, level);
            return;
        }

        final long seed = level.getSeed();
        final RandomSource random = new XoroshiroRandomSource(seed);

        final RegionGenerator regionGenerator = new RegionGenerator(settings, random);
        final ChunkDataGenerator chunkDataGenerator = RegionChunkDataGenerator.create(random.nextLong(), settings.rockLayerSettings(), regionGenerator);
        final AreaFactory factory = TFCLayers.createRegionBiomeLayer(regionGenerator, random.nextLong());
        final ConcurrentArea<BiomeExtension> biomeLayer = new ConcurrentArea<>(factory, TFCLayers::getFromLayerId);

        this.noiseSamplerSeed = seed;
        this.noiseSampler = new NoiseSampler(random.nextLong(), level.registryAccess().lookupOrThrow(Registries.NOISE), level.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION));
        this.chunkDataProvider = new ChunkDataProvider(chunkDataGenerator);
        this.surfaceManager = new SurfaceManager(seed);

        this.customBiomeSource.initRandomState(regionGenerator, biomeLayer);

        // Update the cached chunk generator extension on the RandomState
        // This is done here when we initialize this chunk generator, and have ensured we are unique to this state and chunk map
        // We do this to be able to access the chunk generator through the random state later, i.e. in structure generation
        ((RandomStateExtension) (Object) ((ChunkMapAccessor) chunkMap).accessor$getRandomState()).tfc$setChunkGeneratorExtension(this);
    }

    public ChunkHeightFiller createHeightFillerForChunk(ChunkPos pos) {
        final Object2DoubleMap<BiomeExtension>[] biomeWeights = ChunkBiomeSampler.sampleBiomes(pos, this::sampleBiomeNoRiver, BiomeExtension::biomeBlendType);
        return new ChunkHeightFiller(biomeWeights, customBiomeSource, createBiomeSamplersForChunk(null), createRiverSamplersForChunk(), createShoreSamplerForChunk(), getSeaLevel());
    }

    @Override
    protected Codec<TFCCompatibleChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(Executor executor, RandomState state, Blender legacyTerrainBlender, StructureManager structureFeatureManager, ChunkAccess chunk) {
        return super.createBiomes(executor, state, legacyTerrainBlender, structureFeatureManager, chunk);
//        return CompletableFuture.supplyAsync(() -> {
//            chunkDataProvider.get(chunk);
//            chunk.fillBiomesFromNoise((quartX, quartY, quartZ, sampler) -> customBiomeSource.getBiome(quartX, quartZ), NoopClimateSampler.INSTANCE);
//            return chunk;
//        }, Util.backgroundExecutor());
    }

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState state, BiomeManager biomeManager, StructureManager structureFeatureManager, ChunkAccess chunk, GenerationStep.Carving step) {
        super.applyCarvers(level, seed, state, biomeManager, structureFeatureManager, chunk, step);
        // Skip water carving, only do air carving, since we use aquifers
//        if (step != GenerationStep.Carving.AIR)
//        {
//            return;
//        }
//
//        final BiomeManager customBiomeManager = biomeManager.withDifferentSource((x, y, z) -> customBiomeSource.getBiome(x, z));
//        final PositionalRandomFactory fork = new XoroshiroRandomSource(seed).forkPositional();
//        final ChunkPos chunkPos = chunk.getPos();
//
//        final ChunkNoiseSamplingSettings settings = createNoiseSamplingSettingsForChunk(chunk);
//        final ChunkBaseBlockSource baseBlockSource = createBaseBlockSourceForChunk(chunk);
//        final TFCAquifer aquifer = getOrCreateAquifer(chunk, settings, baseBlockSource);
//
//        @SuppressWarnings("ConstantConditions")
//        final CarvingContext context = new CarvingContext(stupidMojangChunkGenerator, null, chunk.getHeightAccessorForGeneration(), null, state, this.noiseSettings.value().surfaceRule());
//        final CarvingMask carvingMask = ((ProtoChunk) chunk).getOrCreateCarvingMask(step);
//
//        for (int offsetX = -8; offsetX <= 8; ++offsetX)
//        {
//            for (int offsetZ = -8; offsetZ <= 8; ++offsetZ)
//            {
//                final ChunkPos offsetChunkPos = new ChunkPos(chunkPos.x + offsetX, chunkPos.z + offsetZ);
//                final ChunkAccess offsetChunk = level.getChunk(offsetChunkPos.x, offsetChunkPos.z);
//
//                @SuppressWarnings("deprecation")
//                final Iterable<Holder<ConfiguredWorldCarver<?>>> iterable = offsetChunk
//                        .carverBiome(() -> customBiomeSource.getBiome(QuartPos.fromBlock(offsetChunkPos.getMinBlockX()), QuartPos.fromBlock(offsetChunkPos.getMinBlockZ())).value().getGenerationSettings())
//                        .getCarvers(step);
//
//                int i = 1;
//                for (Holder<ConfiguredWorldCarver<?>> holder : iterable)
//                {
//                    final RandomSource chunkRandom = fork.at(offsetChunkPos.x, i, offsetChunkPos.z);
//
//                    final ConfiguredWorldCarver<?> carver = holder.value();
//                    if (carver.isStartChunk(chunkRandom))
//                    {
//                        carver.carve(context, chunk, customBiomeManager::getBiome, chunkRandom, aquifer, offsetChunkPos, carvingMask);
//                    }
//                    i++;
//                }
//            }
//        }
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureFeatureManager) {
        final ChunkPos chunkPos = chunk.getPos();
        final SectionPos sectionPos = SectionPos.of(chunkPos, level.getMinSection());
        final BlockPos originPos = sectionPos.origin();

        final Registry<Structure> structureFeatures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        final Map<Integer, List<Structure>> structureFeaturesByStep = structureFeatures.stream()
                .collect(Collectors.groupingBy(feature -> feature.step().ordinal()));

        final List<FeatureSorter.StepFeatureData> orderedFeatures = ((ChunkGeneratorAccessor) this).accessor$getFeaturesPerStep().get();
        final WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        final long baseSeed = Helpers.hash(128739412341L, originPos);

        final Set<Biome> allAdjacentBiomes = new ObjectArraySet<>();
        ChunkPos.rangeClosed(sectionPos.chunk(), 1).forEach((chunkPos1_) -> {
            final ChunkAccess adjChunk = level.getChunk(chunkPos1_.x, chunkPos1_.z);
            for (LevelChunkSection adjSection : adjChunk.getSections()) {
                adjSection.getBiomes().getAll(biome -> allAdjacentBiomes.add(biome.value()));
            }
        });

        for (int decorationIndex = 0; decorationIndex < Math.max(DECORATION_STEPS, orderedFeatures.size()); ++decorationIndex) {
            if (structureFeatureManager.shouldGenerateStructures()) {
                int featureIndex = 0;
                for (Structure feature : structureFeaturesByStep.getOrDefault(decorationIndex, Collections.emptyList())) {
                    Helpers.seedLargeFeatures(random, baseSeed, featureIndex, decorationIndex);

                    structureFeatureManager
                            .startsForStructure(sectionPos, feature)
                            .forEach(start -> start.placeInChunk(level, structureFeatureManager, this, random, getBoundingBoxForStructure(chunk), chunkPos));
                    featureIndex++;
                }
            }

            if (decorationIndex < orderedFeatures.size()) {
                final IntSet featureIndices = new IntArraySet();
                for (Biome biome : allAdjacentBiomes) {
                    List<HolderSet<PlacedFeature>> featuresPerBiome = TFCBiomes.getExtensionOrThrow(level, biome).getFlattenedFeatures(biome);
                    if (decorationIndex < featuresPerBiome.size()) {
                        final HolderSet<PlacedFeature> featuresPerBiomeAtStep = featuresPerBiome.get(decorationIndex);
                        final FeatureSorter.StepFeatureData stepIndex = orderedFeatures.get(decorationIndex);
                        for (Holder<PlacedFeature> holder : featuresPerBiomeAtStep) {
                            featureIndices.add(stepIndex.indexMapping().applyAsInt(holder.value()));
                        }
                    }
                }

                final int[] sortedIndices = featureIndices.toIntArray();
                final FeatureSorter.StepFeatureData step = orderedFeatures.get(decorationIndex);

                Arrays.sort(sortedIndices);
                for (int featureIndex : sortedIndices) {
                    Helpers.seedLargeFeatures(random, baseSeed, featureIndex, decorationIndex);
                    step.features()
                            .get(featureIndex)
                            .placeWithBiomeCheck(level, this, random, originPos);
                }
            }
        }

        level.setCurrentlyGenerating(null);
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureFeatureManager, RandomState state, ChunkAccess chunk) {
        super.buildSurface(level, structureFeatureManager, state, chunk);
        // makeBedrock(chunk);
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
        if (!this.noiseSettings.value().disableMobGeneration()) {
            final ChunkPos pos = level.getCenter();
            final Holder<Biome> biome = level.getBiome(pos.getWorldPosition().atY(level.getMaxBuildHeight() - 1));
            final WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
            random.setDecorationSeed(level.getSeed(), pos.getMinBlockX(), pos.getMinBlockZ());

            NaturalSpawner.spawnMobsForChunkGeneration(level, biome, pos, random);
        }
    }

    @Override
    public BiomeSource getBiomeSource() {
        return customBiomeSource.self();
    }

    @Override
    public int getGenDepth() {
        return this.noiseSettings.value().noiseSettings().height();
    }

    @Override
    public void createStructures(RegistryAccess dynamicRegistry, ChunkGeneratorStructureState structureState, StructureManager structureFeatureManager, ChunkAccess chunk, StructureTemplateManager templateManager) {
        chunkDataProvider.get(chunk); // populate chunk data before references to enable placements
        super.createStructures(dynamicRegistry, structureState, structureFeatureManager, chunk, templateManager);
    }

    @Override
    public void createReferences(WorldGenLevel level, StructureManager structureFeatureManager, ChunkAccess chunk) {
        super.createReferences(level, structureFeatureManager, chunk);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor mainExecutor, Blender oldTerrainBlender, RandomState rawState, StructureManager structureFeatureManager, ChunkAccess chunk) {
        CompletableFuture<ChunkAccess> noiseGen = super.fillFromNoise(mainExecutor, oldTerrainBlender, rawState, structureFeatureManager, chunk);
        
        // Initialization
        final ChunkNoiseSamplingSettings settings = createNoiseSamplingSettingsForChunk(chunk);
        final LevelAccessor actualLevel = (LevelAccessor) ((ChunkAccessAccessor) chunk).accessor$getLevelHeightAccessor();
        final ChunkPos chunkPos = chunk.getPos();
        final RandomSource random = new XoroshiroRandomSource(chunkPos.x * 1842639486192314L, chunkPos.z * 579238196380231L);
        final ChunkData chunkData = chunkDataProvider.get(chunk);

        // Lock sections
        final Set<LevelChunkSection> sections = new HashSet<>();
        for (LevelChunkSection section : chunk.getSections()) {
//            section.acquire();
            sections.add(section);
        }

        final Object2DoubleMap<BiomeExtension>[] biomeWeights = ChunkBiomeSampler.sampleBiomes(chunkPos, this::sampleBiomeNoRiver, BiomeExtension::biomeBlendType);
        final ChunkBaseBlockSource baseBlockSource = createBaseBlockSourceForChunk(chunk);
        final ChunkNoiseFiller filler = new ChunkNoiseFiller((ProtoChunk) chunk, biomeWeights, customBiomeSource, createBiomeSamplersForChunk(chunk), createRiverSamplersForChunk(), createShoreSamplerForChunk(), noiseSampler, baseBlockSource, settings, getSeaLevel(), Beardifier.forStructuresInChunk(structureFeatureManager, chunkPos));

        return CompletableFuture.supplyAsync(() -> {
            filler.sampleAquiferSurfaceHeight(this::sampleBiomeNoRiver);
            chunkData.generateFull(filler.surfaceHeight(), filler.aquifer().surfaceHeights());
            chunkData.getRockData().useCache(chunkPos);
            //filler.fillFromNoise();

            aquiferCache.set(chunkPos.x, chunkPos.z, filler.aquifer());

            return chunk;
        }, Util.backgroundExecutor()).thenCompose(c -> noiseGen);
    }

    @Override
    public int getSeaLevel() {
        return super.getSeaLevel();
    }

    @Override
    public int getMinY() {
        return super.getMinY();
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState state) {
        return super.getBaseHeight(x, z, type, level, state);
//        final ChunkPos pos = new ChunkPos(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
//        return (int) createHeightFillerForChunk(pos).sampleHeight(x, z);
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState state) {
        // return new NoiseColumn(0, new BlockState[0]);
        return super.getBaseColumn(x, z, level, state);
    }

    @Override
    public void addDebugScreenInfo(List<String> list, RandomState state, BlockPos pos) {
        //list.add("Shore: " + createShoreSamplerForChunk().noise(pos.getX(), pos.getZ()));
        super.addDebugScreenInfo(list, state, pos);
    }

    /**
     * Builds either a single flat layer of bedrock, or natural vanilla bedrock
     * Writes directly to the bottom chunk section for better efficiency
     */
    private void makeBedrock(ChunkAccess chunk) {
        final ChunkPos chunkPos = chunk.getPos();
        final RandomSource random = new XoroshiroRandomSource(chunkPos.x * 2369412341L, chunkPos.z * 8192836412341L);
        final LevelChunkSection bottomSection = chunk.getSection(0);
        final BlockState bedrock = Blocks.BEDROCK.defaultBlockState();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (settings.flatBedrock()) {
                    bottomSection.setBlockState(x, 0, z, bedrock, false);
                } else {
                    for (int y = 0; y < 6; y++) {
                        if (random.nextInt(6) < 6 - y) {
                            bottomSection.setBlockState(x, y, z, bedrock, false);
                        }
                    }
                }
            }
        }
    }

    private BoundingBox getBoundingBoxForStructure(ChunkAccess chunk) {
        final ChunkPos pos = chunk.getPos();
        final int blockX = pos.getMinBlockX(), blockZ = pos.getMinBlockZ();
        final LevelHeightAccessor level = chunk.getHeightAccessorForGeneration();
        return new BoundingBox(blockX, level.getMinBuildHeight() + 1, blockZ, blockX + 15, level.getMaxBuildHeight() - 1, blockZ + 15);
    }

    private BiomeExtension sampleBiomeNoRiver(int blockX, int blockZ) {
        return customBiomeSource.getBiomeExtensionNoRiver(QuartPos.fromBlock(blockX), QuartPos.fromBlock(blockZ));
    }

    private ChunkBaseBlockSource createBaseBlockSourceForChunk(ChunkAccess chunk) {
        final RockData rockData = chunkDataProvider.get(chunk).getRockData();
        return new ChunkBaseBlockSource(rockData, this::sampleBiomeNoRiver);
    }

    private ChunkNoiseSamplingSettings createNoiseSamplingSettingsForChunk(ChunkAccess chunk) {
        return createNoiseSamplingSettingsForChunk(chunk.getPos(), chunk.getHeightAccessorForGeneration());
    }

    private ChunkNoiseSamplingSettings createNoiseSamplingSettingsForChunk(ChunkPos pos, LevelHeightAccessor level) {
        final NoiseSettings noiseSettings = this.noiseSettings.value().noiseSettings();

        final int cellWidth = noiseSettings.getCellWidth();
        final int cellHeight = noiseSettings.getCellHeight();

        final int minY = Math.max(noiseSettings.minY(), level.getMinBuildHeight());
        final int maxY = Math.min(noiseSettings.minY() + noiseSettings.height(), level.getMaxBuildHeight());

        final int cellCountY = Math.floorDiv(maxY - minY, noiseSettings.getCellHeight());

        final int firstCellX = Math.floorDiv(pos.getMinBlockX(), cellWidth);
        final int firstCellY = Math.floorDiv(minY, cellHeight);
        final int firstCellZ = Math.floorDiv(pos.getMinBlockZ(), cellWidth);

        return new ChunkNoiseSamplingSettings(minY, 16 / cellWidth, cellCountY, cellWidth, cellHeight, firstCellX, firstCellY, firstCellZ);
    }

    private TFCAquifer getOrCreateAquifer(ChunkAccess chunk, ChunkNoiseSamplingSettings settings, ChunkBaseBlockSource baseBlockSource) {
        final ChunkPos chunkPos = chunk.getPos();

        TFCAquifer aquifer = aquiferCache.getIfPresent(chunkPos.x, chunkPos.z);
        if (aquifer == null) {
            final ChunkData chunkData = chunkDataProvider.get(chunk);

            aquifer = new TFCAquifer(chunkPos, settings, baseBlockSource, getSeaLevel(), noiseSampler.positionalRandomFactory, noiseSampler.barrierNoise);
            aquifer.setSurfaceHeights(chunkData.getAquiferSurfaceHeight());

            aquiferCache.set(chunkPos.x, chunkPos.z, aquifer);
        }
        return aquifer;
    }

    private Map<BiomeExtension, BiomeNoiseSampler> createBiomeSamplersForChunk(@Nullable ChunkAccess chunk) {
        final ImmutableMap.Builder<BiomeExtension, BiomeNoiseSampler> builder = ImmutableMap.builder();
        for (BiomeExtension extension : TFCBiomes.getExtensions()) {
            final BiomeNoiseSampler sampler = extension.createNoiseSampler(noiseSamplerSeed);
            if (sampler != null) {
                sampler.prepare(this, chunk);
                builder.put(extension, sampler);
            }
        }
        return builder.build();
    }

    private Map<RiverBlendType, RiverNoiseSampler> createRiverSamplersForChunk() {
        final EnumMap<RiverBlendType, RiverNoiseSampler> builder = new EnumMap<>(RiverBlendType.class);
        for (RiverBlendType blendType : RiverBlendType.ALL) {
            builder.put(blendType, blendType.createNoiseSampler(noiseSamplerSeed));
        }
        return builder;
    }

    private Noise2D createShoreSamplerForChunk() {
        return new OpenSimplex2D(noiseSamplerSeed)
                .octaves(2)
                .spread(0.003f)
                .scaled(-0.1, 1.1);
    }

    private TFCChunkGenerator copy() {
        return new TFCChunkGenerator(customBiomeSource.copy(), noiseSettings, settings);
    }

}
