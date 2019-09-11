package io.cubyz.blocks;

import org.joml.Vector3i;

import io.cubyz.api.GameRegistry;
import io.cubyz.world.World;

public class WorkBench extends Block {
	
	public WorkBench() {
		super("cubyz:workbench");
		texConverted = true; // texture already in runtime format
		inventorySize = 6;
	}
	
	public boolean onClick(World world, Vector3i pos, BlockInstance bi) {
		GameRegistry.openGUI("cubyz:workbench", bi.getInventory());
		return true;
	}
	
}