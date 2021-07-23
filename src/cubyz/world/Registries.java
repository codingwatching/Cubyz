package cubyz.world;

import cubyz.utils.datastructures.DataOrientedRegistry;
import cubyz.utils.datastructures.Registry;
import cubyz.world.terrain.BiomeRegistry;
import cubyz.world.blocks.RotationMode;
import cubyz.world.entity.Effect;
import cubyz.world.entity.EntityComponent;
import cubyz.world.entity.EntityType;
import cubyz.world.items.Item;
import cubyz.world.terrain.Generator;
import cubyz.world.terrain.structures.BiomeStructure;

/**
 * All the registries of the current universe.
 */

public class Registries {
	// Registries that may change per world:
	public static final Registry<Item> ITEMS = new Registry<Item>();
	public static final Registry<EntityType> ENTITIES = new Registry<EntityType>();
	public static final BiomeRegistry BIOMES = new BiomeRegistry();
	public static final Registry<Effect> EFFECTS = new Registry<Effect>();
	public static final Registry<EntityComponent> ENTITY_COMPONENTS = new Registry<EntityComponent>();
	
	/** Contains all terrain generators. */
	public static final Registry<Generator> GENERATORS = new Registry<Generator>();
	/** Contains all small and biome-dependent structures such as trees. */
	public static final Registry<BiomeStructure> BIOME_STRUCTURES = new Registry<BiomeStructure>();
	
	// Registries of Registries:
	public static final Registry<DataOrientedRegistry> BLOCK_REGISTRIES = new Registry<DataOrientedRegistry>();
	
	// Registries that stay constant:
	public static final Registry<RotationMode> ROTATION_MODES = new Registry<RotationMode>();
	
	
	
	public static void clear() {
		ITEMS.clear();
		ROTATION_MODES.clear();
		ENTITIES.clear();
		ENTITY_COMPONENTS.clear();
		BIOMES.clear();
		EFFECTS.clear();
		
		for(DataOrientedRegistry registry : BLOCK_REGISTRIES.toArray(new DataOrientedRegistry[0])) {
			registry.clear();
		}
	}
}