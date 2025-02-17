package raccoonman.reterraforged.forge.tfc.noise;

import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.dries007.tfc.common.fluids.RiverWaterFluid;
import net.dries007.tfc.common.fluids.TFCFluids;
import net.dries007.tfc.world.*;
import net.dries007.tfc.world.biome.BiomeExtension;
import net.dries007.tfc.world.biome.BiomeSourceExtension;
import net.dries007.tfc.world.noise.*;
import net.dries007.tfc.world.region.RegionPartition;
import net.dries007.tfc.world.river.Flow;
import net.dries007.tfc.world.river.RiverBlendType;
import net.dries007.tfc.world.river.RiverInfo;
import net.dries007.tfc.world.river.RiverNoiseSampler;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;
import raccoonman.reterraforged.RTFCommon;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.function.Supplier;

public class TFCChunkNoiseFiller extends ChunkNoiseFiller {

    /**
     * Positions used by the derivative sampling map. This represents a 7x7 grid of 4x4 sub chunks / biome positions, where 0, 0 = -1, -1 relative to the target chunk.
     * Each two pairs of integers is a position, wrapping around the entire outside the chunk (not including the 4x4 of positions in the interior
     */
    public static final int[] EXTERIOR_POINTS = Util.make(new int[2 * (7 * 7 - 4 * 4)], array -> {
        int index = 0;
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                if (x < 1 || z < 1 || x > 4 || z > 4) {
                    array[index++] = x;
                    array[index++] = z;
                }
            }
        }
    });

    public static final int EXTERIOR_POINTS_COUNT = EXTERIOR_POINTS.length >> 1;

    // Initialized from the chunk
    private final ProtoChunk chunk;
    private final int chunkMinX, chunkMinZ; // Min block positions for the chunk
    private final Heightmap oceanFloor, worldSurface;
    private final CarvingMask airCarvingMask; // Only air carving mask is marked

    // Rivers
    private final Beardifier beardifier;
    private final MutableDensityFunctionContext mutableDensityFunctionContext;
    private final FluidState riverWater;
    private final @Nullable RiverInfo[] riverData; // 16 x 16 river info. May be null.
    private final Flow[] riverFlows; // 5 x 5 quart position sampled, pre-interpolated river flows. Not null.

    // Noise interpolation
    private final ChunkNoiseSamplingSettings settings;
    private final NoiseChunk interpolator;

    // Noise Caves
    private final TrilinearInterpolator noiseCaves;

    // Noodle Caves
    private final TFCNoiseInterpolatorWrapper noodleToggle;
    private final TFCNoiseInterpolatorWrapper noodleThickness;
    private final TFCNoiseInterpolatorWrapper noodleRidgeA;
    private final TFCNoiseInterpolatorWrapper noodleRidgeB;

    // Aquifer + Noise -> BlockState
    private final TFCAquifer aquifer;
    private final ChunkBaseBlockSource baseBlockSource;

    private final int[] surfaceHeight; // 16x16, block pos resolution
    private final BiomeExtension[] localBiomes; // 16x16, block pos resolution
    private final BiomeExtension[] localBiomesNoRivers; // 16x16, block pos resolution
    private final double[] localBiomeWeights; // 16x16, block pos resolution

    // Current local position / context
    private double cellDeltaX, cellDeltaZ; // Delta within a noise cell
    private int lastCellZ; // Last cell Z, needed due to a quick in noise interpolator

    public TFCChunkNoiseFiller(ProtoChunk chunk, NoiseChunk interpolator, Object2DoubleMap<BiomeExtension>[] sampledBiomeWeights, BiomeSourceExtension biomeSource, Map<BiomeExtension, BiomeNoiseSampler> biomeNoiseSamplers, Map<RiverBlendType, RiverNoiseSampler> riverNoiseSamplers, Noise2D shoreSampler, NoiseSampler sampler, ChunkBaseBlockSource baseBlockSource, ChunkNoiseSamplingSettings settings, int seaLevel, Beardifier beardifier) {
        super(chunk, sampledBiomeWeights, biomeSource, biomeNoiseSamplers, riverNoiseSamplers, shoreSampler, sampler, baseBlockSource, settings, seaLevel, beardifier);

        this.chunk = chunk;
        this.chunkMinX = chunk.getPos().getMinBlockX();
        this.chunkMinZ = chunk.getPos().getMinBlockZ();
        this.oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        this.worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        this.airCarvingMask = chunk.getOrCreateCarvingMask(GenerationStep.Carving.AIR);

        this.beardifier = beardifier;
        this.mutableDensityFunctionContext = new MutableDensityFunctionContext(new BlockPos.MutableBlockPos());
        this.riverWater = TFCFluids.RIVER_WATER.get().defaultFluidState();
        this.riverData = new RiverInfo[16 * 16];
        this.riverFlows = new Flow[5 * 5];

        sampleRiverData();

        this.settings = settings;
        this.interpolator = interpolator;
        this.baseBlockSource = baseBlockSource;

        // Noise Caves
        this.noiseCaves = new TrilinearInterpolator(settings, sampler.noiseCaves);

        // Noodle Caves
        this.noodleToggle = TFCNoiseInterpolatorWrapper.registerWith(interpolator, sampler.noodleToggle);
        this.noodleThickness = TFCNoiseInterpolatorWrapper.registerWith(interpolator, sampler.noodleThickness);
        this.noodleRidgeA = TFCNoiseInterpolatorWrapper.registerWith(interpolator, sampler.noodleRidgeA);
        this.noodleRidgeB = TFCNoiseInterpolatorWrapper.registerWith(interpolator, sampler.noodleRidgeB);

        // Aquifer
        this.aquifer = new TFCAquifer(chunk.getPos(), settings, baseBlockSource, seaLevel, sampler.positionalRandomFactory, sampler.barrierNoise);

        this.surfaceHeight = new int[16 * 16];
        this.localBiomes = new BiomeExtension[16 * 16];
        this.localBiomesNoRivers = new BiomeExtension[16 * 16];
        this.localBiomeWeights = new double[16 * 16];
    }


    private void sampleRiverData() {
        // Despite sampling river information on a per-block scale, flow gets sampled on a quart scale and interpolated
        // It looks better this way, as flow is not otherwise interpolated and this avoids some harsher transitions between line segments.

        final RegionPartition.Point point = biomeSource.getPartition(chunkMinX, chunkMinZ);

        // Sample on a per-block scale, copying the flow into the quart-scale flows as well
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                setupColumn(chunkMinX + localX, chunkMinZ + localZ);
                final RiverInfo info = sampleRiverEdge(point);

                riverData[localX + 16 * localZ] = info;
            }
        }

        // Sample the remaining points outside the chunk (on the right / down edge)
        for (int quartX = 0; quartX < 5; quartX++) {
            for (int quartZ = 0; quartZ < 5; quartZ++) {
                final int localX = quartX << 2;
                final int localZ = quartZ << 2;

                // Copy from the river data, if in range.
                // Technically there is an edge case were the partition point is actually one block out of range, but it shouldn't matter
                final RiverInfo info;
                if (quartX < 4 && quartZ < 4) {
                    info = riverData[localX + 16 * localZ];
                } else {
                    setupColumn(chunkMinX + localX, chunkMinZ + localZ);
                    info = sampleRiverEdge(point);
                }

                riverFlows[quartX + 5 * quartZ] = info != null && info.normDistSq() < 0.28 ? info.flow() : Flow.NONE;
            }
        }
    }

    @Override
    public int[] surfaceHeight() {
        return surfaceHeight;
    }

    @Override
    public BiomeExtension[] localBiomes() {
        return localBiomes;
    }

    @Override
    public BiomeExtension[] localBiomesNoRivers() {
        return localBiomesNoRivers;
    }

    @Override
    public double[] localBiomeWeights() {
        return localBiomeWeights;
    }

    @Override
    public void fillFromNoise() {
        interpolator.initializeForFirstCellX();
        final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int cellX = 0; cellX < settings.cellCountXZ(); cellX++) {
            interpolator.advanceCellX(cellX);
            for (int cellZ = 0; cellZ < settings.cellCountXZ(); cellZ++) {
                // skip cell Y
                for (int localCellX = 0; localCellX < settings.cellWidth(); localCellX++) {
                    blockX = chunkMinX + cellX * settings.cellWidth() + localCellX;
                    localX = blockX & 15;
                    cellDeltaX = (double) localCellX / settings.cellWidth();

                    // cannot update for x here because we first need to update for yz. So we do all three each time per cell
                    for (int localCellZ = 0; localCellZ < settings.cellWidth(); localCellZ++) {
                        blockZ = chunkMinZ + cellZ * settings.cellWidth() + localCellZ;
                        lastCellZ = cellZ; // needed for the noise interpolator
                        localZ = blockZ & 15;
                        cellDeltaZ = (double) localCellZ / settings.cellWidth();

                        mutablePos.set(blockX, 0, blockZ);
                        fillColumn(mutablePos, cellX, cellZ);
                    }
                }
            }
            interpolator.swapSlices();
        }
    }


    private void fillColumn(BlockPos.MutableBlockPos cursor, int cellX, int cellZ) {
        boolean debugFillColumn = false;

        super.prepareColumnBiomeWeights();
        super.biomeWeights1.forEach((k,v) -> {
            RTFCommon.LOGGER.info("biomeWeights1[" + k.key() + "] = " + v);
        });
        super.sampleColumnHeightAndBiome(super.biomeWeights1, true);

        int localIndex = this.localX + 16 * this.localZ;
        int heightNoiseValue = this.surfaceHeight[localIndex];

        BiomeExtension localBiome = this.localBiomes[localIndex];
        Flow flow = localBiome.hasRivers() ? this.calculateFlowAt(cellX, cellZ) : Flow.NONE;

        final int maxFilledY = 1 + Math.max(heightNoiseValue, seaLevel);
        final int maxFilledCellY = Math.min(settings.cellCountY() - 1, 1 + Math.floorDiv(maxFilledY, settings.cellHeight()) - settings.firstCellY());
        final int maxFilledSectionY = Math.min(chunk.getSectionsCount() - 1, 1 + chunk.getSectionIndex(maxFilledY));

        boolean topBlockPlaced = false;
        boolean topSolidBlockPlaced = false;

        LevelChunkSection section = this.chunk.getSection(maxFilledSectionY);
        int lastSectionIndex = maxFilledSectionY;

        for (int cellY = maxFilledCellY; cellY >= 0; --cellY) {
            this.interpolator.selectCellYZ(cellY, this.lastCellZ);
            this.interpolator.updateForX(cellX, this.cellDeltaX);
            this.interpolator.updateForZ(cellZ, this.cellDeltaZ);

            for (int localCellY = this.settings.cellHeight() - 1; localCellY >= 0; --localCellY) {
                int y = (this.settings.firstCellY() + cellY) * this.settings.cellHeight() + localCellY;
                if (y < maxFilledY) {
                    int localY = y & 15;
                    int sectionIndex = this.chunk.getSectionIndex(y);
                    if (lastSectionIndex != sectionIndex) {
                        section = this.chunk.getSection(sectionIndex);
                        lastSectionIndex = sectionIndex;
                    }

                    double cellDeltaY = (double) localCellY / (double) this.settings.cellHeight();
                    this.interpolator.updateForY(cellY, cellDeltaY);
                    double noise = this.calculateNoiseAtHeight(y, (double) heightNoiseValue);
                    BlockState state = this.calculateBlockStateAtNoise(y, noise);
                    FluidState fluid = state.getFluidState();
                    cursor.setY(y);
                    if (!state.isAir()) {
                        if (fluid.getType() == Fluids.WATER && flow != Flow.NONE && y >= Math.min(this.seaLevel - 4, heightNoiseValue)) {
                            section.setBlockState(this.localX, localY, this.localZ, ((FluidState) this.riverWater.setValue(RiverWaterFluid.FLOW, flow)).createLegacyBlock(), false);
                        } else {
                            section.setBlockState(this.localX, localY, this.localZ, state, false);
                        }

                        if (this.aquifer.shouldScheduleFluidUpdate() && !fluid.isEmpty()) {
                            this.chunk.markPosForPostprocessing(cursor);
                        }
                    }

                    if (state.isAir()) {
                        if (topSolidBlockPlaced) {
                            this.airCarvingMask.set(this.blockX, y, this.blockZ);
                            section.setBlockState(this.localX, localY, this.localZ, Blocks.CAVE_AIR.defaultBlockState(), false);
                        }
                    } else if (!fluid.isEmpty()) {
                        if (!topBlockPlaced) {
                            topBlockPlaced = true;
                            this.worldSurface.update(this.localX, y, this.localZ, state);
                        }

                        if (topSolidBlockPlaced) {
                            this.airCarvingMask.set(this.blockX, y, this.blockZ);
                        }
                    } else {
                        if (!topBlockPlaced) {
                            topBlockPlaced = true;
                            this.worldSurface.update(this.localX, y, this.localZ, state);
                        }

                        if (!topSolidBlockPlaced) {
                            topSolidBlockPlaced = true;
                            this.oceanFloor.update(this.localX, y, this.localZ, state);
                        }
                    }
                }
            }
        }

    }

    private Flow calculateFlowAt(int cellX, int cellZ) {
        // Interpolate flow for this column
        final Flow flow00 = riverFlows[cellX + 5 * cellZ];
        final Flow flow10 = riverFlows[cellX + 5 * (cellZ + 1)];
        final Flow flow01 = riverFlows[(cellX + 1) + 5 * cellZ];
        final Flow flow11 = riverFlows[(cellX + 1) + 5 * (cellZ + 1)];

        return Flow.lerp(flow00, flow01, flow10, flow11, (float) cellDeltaX, (float) cellDeltaZ);
    }

    /**
     * @param y                The y position
     * @param heightNoiseValue The calculated average height noise, from {@link BiomeNoiseSampler#height()}
     * @return The density noise for the given y position, where positive noise is solid, in the range [0, 1]
     */
    private double calculateNoiseAtHeight(int y, double heightNoiseValue) {
        double noise = 0;
        for (Object2DoubleMap.Entry<BiomeNoiseSampler> entry : columnBiomeNoiseSamplers.object2DoubleEntrySet()) {
            // Positive values = air
            final BiomeNoiseSampler sampler = entry.getKey();
            noise += sampler.noise(y) * entry.getDoubleValue();
        }

        // Apply transformations from rivers
        // Each river blend type applies to the initial noise value, and then is weighted by its blend weight
        final double initialNoise = noise;
        noise = 0;
        for (RiverBlendType type : RiverBlendType.ALL) {
            final double weight = riverBlendWeights[type.ordinal()];
            if (type == RiverBlendType.NONE) {
                noise += weight * initialNoise;
            } else if (weight > 0) {
                final RiverNoiseSampler sampler = riverNoiseSamplers.get(type);
                noise += weight * sampler.noise(y, initialNoise);
            }
        }

        noise = BiomeNoiseSampler.AIR_THRESHOLD - noise; // Positive noise = solid
        if (y > heightNoiseValue) {
            // Slide down if we're above the expected height
            noise -= (y - heightNoiseValue) * 0.2f;
        }

        return Mth.clamp(noise, -1, 1);
    }

    /**
     * @param terrainNoise The terrain noise for the position. Positive values indicate solid terrain, in the range [-1, 1]
     * @return The block state for the position, including the aquifer, noise and noodle caves, and terrain.
     */
    private BlockState calculateBlockStateAtNoise(int y, double terrainNoise) {
        double terrainAndCaveNoise = terrainNoise;
        if (noodleToggle.sample() >= 0) {
            final double thickness = Mth.clampedMap(noodleThickness.sample(), -1, 1, 0.05, 0.1);
            final double ridgeA = Math.abs(1.5 * noodleRidgeA.sample()) - thickness;
            final double ridgeB = Math.abs(1.5 * noodleRidgeB.sample()) - thickness;
            final double ridge = Math.max(ridgeA, ridgeB);

            terrainAndCaveNoise = Math.min(terrainAndCaveNoise, ridge);
        }

        terrainAndCaveNoise = Math.min(terrainAndCaveNoise, noiseCaves.sample());
        mutableDensityFunctionContext.cursor().set(blockX, y, blockZ);
        terrainAndCaveNoise += beardifier.compute(mutableDensityFunctionContext);

        final BlockState aquiferState = aquifer.sampleState(blockX, y, blockZ, terrainAndCaveNoise);
        if (aquiferState != null) {
            return aquiferState;
        }
        return baseBlockSource.getBaseBlock(blockX, y, blockZ);
    }
}
