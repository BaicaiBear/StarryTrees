package de.dafuqs.starryskies;

import net.minecraft.util.math.*;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.*;
import org.jetbrains.annotations.*;

import java.util.*;

import java.awt.Point;

public class Support {

	public static Point getSystemCoordinateFromChunkCoordinate(int chunkX, int chunkZ) {
		int systemSize = StarrySkies.CONFIG.systemSizeChunks;
		int x = (int) Math.floor((double) chunkX / systemSize);
		int z = (int) Math.floor((double) chunkZ / systemSize);
		return new Point(x, z);
	}

	/**
	 * Returns a random number between lowest and highest
	 *
	 * @param lowest  The lowest number (inclusive)
	 * @param highest The highest number (inclusive)
	 * @return The random number between lowest and highest
	 */
	public static int getRandomBetween(@NotNull Random random, int lowest, int highest) {
		return lowest + random.nextInt(highest - lowest + 1);
	}

	public static float getRandomBetween(@NotNull Random random, float lowest, float highest) {
		return lowest + random.nextFloat() * (highest - lowest);
	}

	public static double getDistance(double x1, double y1, double z1, double x2, double y2, double z2) {
		return Math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2) + (z1 - z2) * (z1 - z2));
	}

	public static double getDistance(@NotNull BlockPos blockPos1, @NotNull BlockPos blockpos2) {
		return getDistance(blockPos1.getX(), blockPos1.getY(), blockPos1.getZ(), blockpos2.getX(), blockpos2.getY(),
				blockpos2.getZ());
	}

	public static boolean isBlockPosInChunkPos(@NotNull ChunkPos chunkPos, @NotNull BlockPos blockPos) {
		return (blockPos.getX() >= chunkPos.getStartX()
				&& blockPos.getX() < chunkPos.getStartX() + 16
				&& blockPos.getZ() >= chunkPos.getStartZ()
				&& blockPos.getZ() < chunkPos.getStartZ() + 16);
	}

	public static int getLowerGroundBlock(WorldAccess world, @NotNull BlockPos position, int minHeight) {
		BlockPos.Mutable blockPos$Mutable = new BlockPos.Mutable(position.getX(), position.getY(), position.getZ());

		// if height is an air block, move down until we reached a solid block. We are
		// now on the surface of a piece of land
		while (blockPos$Mutable.getY() > minHeight) {
			if (!world.isAir(blockPos$Mutable)) {
				break;
			}
			blockPos$Mutable.move(Direction.DOWN);
		}
		return blockPos$Mutable.getY();
	}

	public static int getUpperGroundBlock(WorldAccess world, @NotNull BlockPos position, int minHeight) {
		BlockPos.Mutable blockPos$Mutable = new BlockPos.Mutable(position.getX(), position.getY(), position.getZ());

		// if height is an air block, move down until we reached a solid block. We are
		// now on the surface of a piece of land
		while (blockPos$Mutable.getY() > minHeight) {
			if (!world.isAir(blockPos$Mutable)) {
				return blockPos$Mutable.getY();
			}
			blockPos$Mutable.move(Direction.UP);
		}
		return -1;
	}

	public static <E> E getWeightedRandom(@NotNull Map<E, Float> weights, Random random) {
		E result = null;
		double bestValue = Double.MAX_VALUE;

		for (E element : weights.keySet()) {
			double value = -Math.log(random.nextDouble()) / (weights.get(element));

			if (value < bestValue) {
				bestValue = value;
				result = element;
			}
		}
		return result;
	}

}
