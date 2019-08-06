package io.cubyz.blocks;

import io.cubyz.items.Item;

public class CobbleStone extends Block {
	public CobbleStone() {
		setTexture("cobblestone");
		setID("cubyz:cobblestone");
		Item bd = new Item();
		bd.setBlock(this);
		bd.setID("cubyz_items:cobblestone");
		bd.setTexture("blocks/"+getTexture()+".png");
		setBlockDrop(bd);
	}
}
