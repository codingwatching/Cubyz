package cubyz.world.blocks;

/**
 * Ores can be found underground in veins.<br>
 * TODO: Add support for non-stone ores.
 */

public class Ore {
	/**average size of a vein in blocks*/
	public final float size;
	/**average density of a vein*/
	public final float density;
	/**average veins per chunk*/
	public final float veins;
	/**maximum height this ore can be generated*/
	public final int maxHeight;

	public final Block block;

	public final Block[] sources;

	public Ore(Block block, Block[] sources, int maxHeight, float veins, float size, float density) {
		this.block = block;
		this.sources = sources;
		this.maxHeight = maxHeight;
		this.veins = veins;
		this.size = size;
		this.density = Math.max(0.05f, Math.min(density, 1));
	}

	public boolean canCreateVeinInBlock(Block block) {
		for(Block src : sources) {
			if(src == block) return true;
		}
		return false;
	}
}