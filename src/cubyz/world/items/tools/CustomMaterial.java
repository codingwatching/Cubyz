package cubyz.world.items.tools;

import cubyz.api.CurrentWorldRegistries;
import cubyz.world.items.Item;

/**
 * Tool material for a randomly generated ore.
 */

public class CustomMaterial extends MaterialOld {
	private int color;
	public CustomMaterial(int heDur, int bDur, int haDur, float dmg, float spd, int color, Item item, int value, CurrentWorldRegistries registries) {
		super(heDur, bDur, haDur, dmg, spd);
		this.color = color;
		addItem(item, value);
		setID(item.getRegistryID());
		registries.materialRegistry.register(this);
	}
	public int getColor() {
		return color;
	}
	public String getName() { // Add color information to the name.
		return "template"+"|"+color+"|";
	}
}
