package raccoonman.reterraforged.forge.tfc.noise;

import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.dries007.tfc.common.fluids.RiverWaterFluid;
import net.dries007.tfc.world.BiomeNoiseSampler;
import net.dries007.tfc.world.ChunkBaseBlockSource;
import net.dries007.tfc.world.ChunkNoiseFiller;
import net.dries007.tfc.world.biome.BiomeExtension;
import net.dries007.tfc.world.biome.BiomeSourceExtension;
import net.dries007.tfc.world.noise.ChunkNoiseSamplingSettings;
import net.dries007.tfc.world.noise.Noise2D;
import net.dries007.tfc.world.noise.NoiseSampler;
import net.dries007.tfc.world.river.Flow;
import net.dries007.tfc.world.river.RiverBlendType;
import net.dries007.tfc.world.river.RiverNoiseSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.Map;
import java.util.function.Supplier;

public class TFCChunkNoiseFiller extends ChunkNoiseFiller {
    protected final ChunkNoiseSamplingSettings settings;

    private final Flow[] riverFlows; // 5 x 5 quart position sampled, pre-interpolated river flows. Not null.
    protected final int chunkMinX, chunkMinZ; // min block positions for the chunk
    private final int[] surfaceHeight; // 16x16, block pos resolution
    private final BiomeExtension[] localBiomes; // 16x16, block pos resolution
    private final BiomeExtension[] localBiomesNoRivers; // 16x16, block pos resolution
    private final double[] localBiomeWeights; // 16x16, block pos resolution
    protected double cellDeltaX, cellDeltaZ; // delta within a noise cell
    protected int lastCellZ; // last cell Z, needed due to a quick in noise interpolator
    protected final NoiseChunk interpolator;

    public TFCChunkNoiseFiller(ProtoChunk chunk, NoiseChunk interpolator, Object2DoubleMap<BiomeExtension>[] sampledBiomeWeights, BiomeSourceExtension biomeSource, Map<BiomeExtension, BiomeNoiseSampler> biomeNoiseSamplers, Map<RiverBlendType, RiverNoiseSampler> riverNoiseSamplers, Noise2D shoreSampler, NoiseSampler sampler, ChunkBaseBlockSource baseBlockSource, ChunkNoiseSamplingSettings settings, int seaLevel, Beardifier beardifier) {
        super(chunk, sampledBiomeWeights, biomeSource, biomeNoiseSamplers, riverNoiseSamplers, shoreSampler, sampler, baseBlockSource, null, seaLevel, beardifier);

        this.settings = settings;
        this.chunkMinX = chunk.getPos().getMinBlockX();
        this.chunkMinZ = chunk.getPos().getMinBlockZ();

        this.interpolator = interpolator;

        this.riverFlows = new Flow[5 * 5];

        this.surfaceHeight = new int[16 * 16];
        this.localBiomes = new BiomeExtension[16 * 16];
        this.localBiomesNoRivers = new BiomeExtension[16 * 16];
        this.localBiomeWeights = new double[16 * 16];
    }

    @Override
    public int[] surfaceHeight()
    {
        return surfaceHeight;
    }

    @Override
    public BiomeExtension[] localBiomes()
    {
        return localBiomes;
    }

    @Override
    public BiomeExtension[] localBiomesNoRivers()
    {
        return localBiomesNoRivers;
    }

    @Override
    public double[] localBiomeWeights()
    {
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

        this.prepareColumnBiomeWeights();
        this.sampleColumnHeightAndBiome(this.biomeWeights1, true);

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

        for(int cellY = maxFilledCellY; cellY >= 0; --cellY) {
            this.interpolator.selectCellYZ(cellY, this.lastCellZ);
            this.interpolator.updateForXZ(this.cellDeltaX, this.cellDeltaZ);

            for(int localCellY = this.settings.cellHeight() - 1; localCellY >= 0; --localCellY) {
                int y = (this.settings.firstCellY() + cellY) * this.settings.cellHeight() + localCellY;
                if (y < maxFilledY) {
                    int localY = y & 15;
                    int sectionIndex = this.chunk.getSectionIndex(y);
                    if (lastSectionIndex != sectionIndex) {
                        section = this.chunk.getSection(sectionIndex);
                        lastSectionIndex = sectionIndex;
                    }

                    double cellDeltaY = (double)localCellY / (double)this.settings.cellHeight();
                    this.interpolator.updateForY(cellDeltaY);
                    double noise = this.calculateNoiseAtHeight(y, (double)heightNoiseValue);
                    BlockState state = this.calculateBlockStateAtNoise(y, noise);
                    FluidState fluid = state.getFluidState();
                    cursor.setY(y);
                    if (!state.isAir()) {
                        if (fluid.getType() == Fluids.WATER && flow != Flow.NONE && y >= Math.min(this.seaLevel - 4, heightNoiseValue)) {
                            section.setBlockState(this.localX, localY, this.localZ, ((FluidState)this.riverWater.setValue(RiverWaterFluid.FLOW, flow)).createLegacyBlock(), false);
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

    private Flow calculateFlowAt(int cellX, int cellZ)
    {
        // Interpolate flow for this column
        final Flow flow00 = riverFlows[cellX + 5 * cellZ];
        final Flow flow10 = riverFlows[cellX + 5 * (cellZ + 1)];
        final Flow flow01 = riverFlows[(cellX + 1) + 5 * cellZ];
        final Flow flow11 = riverFlows[(cellX + 1) + 5 * (cellZ + 1)];

        return Flow.lerp(flow00, flow01, flow10, flow11, (float) cellDeltaX, (float) cellDeltaZ);
    }
}
