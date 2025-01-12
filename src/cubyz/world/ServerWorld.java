package cubyz.world;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;

import cubyz.Logger;
import cubyz.Settings;
import cubyz.api.CubyzRegistries;
import cubyz.api.CurrentWorldRegistries;
import cubyz.client.ClientSettings;
import cubyz.utils.datastructures.Cache;
import cubyz.utils.datastructures.HashMapKey3D;
import cubyz.utils.math.CubyzMath;
import cubyz.world.blocks.Block;
import cubyz.world.blocks.BlockEntity;
import cubyz.world.blocks.CrystalTextureProvider;
import cubyz.world.blocks.CustomBlock;
import cubyz.world.blocks.Ore;
import cubyz.world.blocks.OreTextureProvider;
import cubyz.world.cubyzgenerators.CrystalCavernGenerator;
import cubyz.world.cubyzgenerators.biomes.Biome;
import cubyz.world.entity.ChunkEntityManager;
import cubyz.world.entity.Entity;
import cubyz.world.entity.ItemEntityManager;
import cubyz.world.entity.Player;
import cubyz.world.generator.LifelandGenerator;
import cubyz.world.generator.SurfaceGenerator;
import cubyz.world.handler.PlaceBlockHandler;
import cubyz.world.handler.RemoveBlockHandler;
import cubyz.world.items.BlockDrop;
import cubyz.world.items.ItemStack;
import cubyz.world.save.WorldIO;
import cubyz.world.terrain.MapFragment;

public class ServerWorld {
	public static final int DAY_CYCLE = 12000; // Length of one in-game day in 100ms. Midnight is at DAY_CYCLE/2. Sunrise and sunset each take about 1/16 of the day. Currently set to 20 minutes
	public static final float GRAVITY = 9.81F*1.5F;

	private MapFragment[] maps;
	private HashMap<HashMapKey3D, MetaChunk> metaChunks = new HashMap<HashMapKey3D, MetaChunk>();
	private NormalChunk[] chunks = new NormalChunk[0];
	private ChunkEntityManager[] entityManagers = new ChunkEntityManager[0];
	private int lastX = Integer.MAX_VALUE, lastY = Integer.MAX_VALUE, lastZ = Integer.MAX_VALUE; // Chunk coordinates of the last chunk update.
	private int lastRegX = Integer.MAX_VALUE, lastRegZ = Integer.MAX_VALUE; // Region coordinates of the last chunk update.
	private int regDRD; // double renderdistance of Region.
	private ArrayList<Entity> entities = new ArrayList<>();
	
	private Block[] blocks;
	
	private SurfaceGenerator generator;
	
	private WorldIO wio;
	
	private List<ChunkGenerationThread> generatorThreads = new ArrayList<>();
	private boolean generated;

	private long gameTime;
	private long milliTime;
	private long lastUpdateTime = System.currentTimeMillis();
	private boolean doGameTimeCycle = true;
	
	private final long seed;

	private final String name;

	private Player player;
	
	float ambientLight = 0f;
	Vector4f clearColor = new Vector4f(0, 0, 0, 1.0f);
	
	// synchronized common list for chunk generation
	private volatile BlockingDeque<ChunkData> loadList = new LinkedBlockingDeque<>();

	// There will be at most 1 GB of reduced chunks in here.
	private static final int CHUNK_CACHE_MASK = 8191;
	private Cache<ReducedChunk> reducedChunkCache = new Cache<ReducedChunk>(new ReducedChunk[CHUNK_CACHE_MASK+1][4]);
	
	public final Class<?> chunkProvider;
	
	boolean liquidUpdate;
	
	BlockEntity[] blockEntities = new BlockEntity[0];
	Integer[] liquids = new Integer[0];
	
	public CurrentWorldRegistries registries;

	private ArrayList<CustomBlock> customBlocks = new ArrayList<>();
	
	private class ChunkGenerationThread extends Thread {
		volatile boolean running = true;
		public void run() {
			while (running) {
				ChunkData popped = null;
				try {
					popped = loadList.take();
				} catch (InterruptedException e) {
					break;
				}
				try {
					synchronousGenerate(popped);
				} catch (Exception e) {
					Logger.error("Could not generate " + popped.voxelSize + "-chunk " + popped.wx + ", " + popped.wy + ", " + popped.wz + " !");
					Logger.error(e);
				}
			}
		}
		
		@Override
		public void interrupt() {
			running = false; // Make sure the Thread stops in all cases.
			super.interrupt();
		}
	}
	
	public ServerWorld(String name, Class<?> chunkProvider) {
		this.name = name;
		registries = new CurrentWorldRegistries();
		this.chunkProvider = chunkProvider;
		// Check if the chunkProvider is valid:
		if(!NormalChunk.class.isAssignableFrom(chunkProvider) ||
				chunkProvider.getConstructors().length != 1 ||
				chunkProvider.getConstructors()[0].getParameterTypes().length != 4 ||
				!chunkProvider.getConstructors()[0].getParameterTypes()[0].equals(Integer.class) ||
				!chunkProvider.getConstructors()[0].getParameterTypes()[1].equals(Integer.class) ||
				!chunkProvider.getConstructors()[0].getParameterTypes()[2].equals(Integer.class) ||
				!chunkProvider.getConstructors()[0].getParameterTypes()[3].equals(ServerWorld.class))
			throw new IllegalArgumentException("Chunk provider "+chunkProvider+" is invalid! It needs to be a subclass of NormalChunk and MUST contain a single constructor with parameters (Integer, Integer, Integer, ServerWorld)");
		maps = new MapFragment[0];
		
		generator = registries.worldGeneratorRegistry.getByID("cubyz:lifeland");
		if (generator instanceof LifelandGenerator) {
			((LifelandGenerator) generator).sortGenerators();
		}
		wio = new WorldIO(this, new File("saves/" + name));
		milliTime = System.currentTimeMillis();
		if (wio.hasWorldData()) {
			seed = wio.loadWorldSeed();
			wio.loadWorldData();
			generated = true;
		} else {
			seed = new Random().nextInt();
			wio.saveWorldData();
		}
	}

	// Returns the blocks, so their meshes can be created and stored.
	public Block[] generate() {
		ArrayList<Block> blockList = new ArrayList<>();
		// Set the IDs again every time a new world is loaded. This is necessary, because the random block creation would otherwise mess with it.
		int ID = 0;
		for (Block b : CubyzRegistries.BLOCK_REGISTRY.registered(new Block[0])) {
			if(!b.isTransparent()) {
				b.ID = ID;
				blockList.add(b);
				ID++;
			}
		}
		// Generate the random ores:
		generate(blockList, ID);

		// Put the truly transparent blocks at the end of the list to make sure the renderer calls the last.
		for (Block b : CubyzRegistries.BLOCK_REGISTRY.registered(new Block[0])) {
			if(b.isTransparent()) {
				b.ID = ID;
				blockList.add(b);
				ID++;
			}
		}
		for (Entity ent : getEntities()) {
			if (ent instanceof Player) {
				player = (Player) ent;
			}
		}
		if (player == null) {
			player = (Player) CubyzRegistries.ENTITY_REGISTRY.getByID("cubyz:player").newEntity(this);
			addEntity(player);
			Random rnd = new Random();
			int dx = 0;
			int dz = 0;
			Logger.info("Finding position..");
			while (true) {
				dx = rnd.nextInt(65536);
				dz = rnd.nextInt(65536);
				Logger.info("Trying " + dx + " ? " + dz);
				if(isValidSpawnLocation(dx, dz))
					break;
			}
			int startY = (int)getMapFragment((int)dx, (int)dz, 1).getHeight(dx, dz);
			seek((int)dx, startY, (int)dz, ClientSettings.RENDER_DISTANCE, ClientSettings.EFFECTIVE_RENDER_DISTANCE*NormalChunk.chunkSize*2);
			player.setPosition(new Vector3i(dx, startY+2, dz));
			Logger.info("OK!");
		}
		wio.saveWorldData();
		blocks = blockList.toArray(new Block[0]);
		LifelandGenerator.initOres(registries.oreRegistry.registered(new Ore[0]));
		for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
			ChunkGenerationThread thread = new ChunkGenerationThread();
			thread.setName("Local-Chunk-Thread-" + i);
			thread.setDaemon(true);
			thread.start();
			generatorThreads.add(thread);
		}
		generated = true;
		return blocks;
	}

	public Player getLocalPlayer() {
		return player;
	}
	
	private int generate(ArrayList<Block> blockList, int ID) {
		Random rand = new Random(seed);
		int randomAmount = 9 + rand.nextInt(3); // TODO
		int i = 0;
		for(i = 0; i < randomAmount; i++) {
			CustomBlock block = CustomBlock.random(rand, registries, new OreTextureProvider());
			customBlocks.add(block);
			blockList.add(block);
			block.ID = ID++;
			registries.blockRegistry.register(block);
		}

		// Create the crystal ore for the CrystalCaverns:
		CustomBlock glowCrystalOre = CustomBlock.random(rand, registries, new OreTextureProvider());
		glowCrystalOre.makeGlow(); // Make sure it glows.
		customBlocks.add(glowCrystalOre);
		blockList.add(glowCrystalOre);
		glowCrystalOre.ID = ID++;
		registries.blockRegistry.register(glowCrystalOre);
		i++;
		// Create the crystal block for the CrystalCaverns:
		CustomBlock crystalBlock = new CustomBlock(new CrystalTextureProvider());
		crystalBlock.setID(glowCrystalOre.getRegistryID().toString()+"_glow_crystal");
		crystalBlock.setHardness(40);
		crystalBlock.addBlockDrop(new BlockDrop(glowCrystalOre.getBlockDrops()[0].item, 4));
		crystalBlock.setLight(glowCrystalOre.color);
		crystalBlock.color = glowCrystalOre.color;
		crystalBlock.seed = glowCrystalOre.seed;
		customBlocks.add(crystalBlock);
		blockList.add(crystalBlock);
		crystalBlock.ID = ID++;
		registries.blockRegistry.register(crystalBlock);
		i++;
		// Init crystal caverns with those two blocks:
		CrystalCavernGenerator.init(crystalBlock, glowCrystalOre);

		wio.loadWorldData(); // load data here in order for entities to also be loaded.
		
		if(generated) {
			wio.saveWorldData();
		}
		generated = true;
		return ID;
	}

	
	public void forceSave() {
		for(MetaChunk chunk : metaChunks.values()) {
			if(chunk != null) chunk.save();
		}
		wio.saveWorldData();
		for(MapFragment map : maps) {
			if(map != null)
				map.mapIO.saveData();
		}
	}
	
	public void addEntity(Entity ent) {
		entities.add(ent);
	}
	
	public void removeEntity(Entity ent) {
		entities.remove(ent);
	}
	
	public void setEntities(Entity[] arr) {
		entities = new ArrayList<>(arr.length);
		for (Entity e : arr) {
			entities.add(e);
		}
	}
	
	public boolean isValidSpawnLocation(int x, int z) {
		// Just make sure there is a forest nearby, so the player will always be able to get the resources needed to start properly.
		// TODO!
		return true;
	}
	
	public void synchronousGenerate(ChunkData ch) {
		if(ch instanceof NormalChunk) {
			((NormalChunk)ch).generateFrom(generator);
			((NormalChunk)ch).load();
		} else {
			ReducedChunkVisibilityData visibilityData = new ReducedChunkVisibilityData(this, ch.wx, ch.wy, ch.wz, ch.voxelSize);
			visibilityData.setMeshListener(ch.meshListener);
			ch.meshListener.accept(visibilityData);
		}
	}
	
	public void removeBlock(int x, int y, int z) {
		NormalChunk ch = getChunk(x >> NormalChunk.chunkShift, y >> NormalChunk.chunkShift, z >> NormalChunk.chunkShift);
		if (ch != null) {
			Block b = ch.getBlock(x & NormalChunk.chunkMask, y & NormalChunk.chunkMask, z & NormalChunk.chunkMask);
			ch.removeBlockAt(x & NormalChunk.chunkMask, y & NormalChunk.chunkMask, z & NormalChunk.chunkMask, true);
			for (RemoveBlockHandler hand : CubyzRegistries.REMOVE_HANDLER_REGISTRY.registered(new RemoveBlockHandler[0])) {
				hand.onBlockRemoved(this, b, x, y, z);
			}
			// Fetch block drops:
			for(BlockDrop drop : b.getBlockDrops()) {
				int amount = (int)(drop.amount);
				float randomPart = drop.amount - amount;
				if(Math.random() < randomPart) amount++;
				if(amount > 0) {
					ItemEntityManager manager = this.getEntityManagerAt(x & ~NormalChunk.chunkMask, y & ~NormalChunk.chunkMask, z & ~NormalChunk.chunkMask).itemEntityManager;
					manager.add(x, y, z, 0, 0, 0, new ItemStack(drop.item, amount), 30*300 /*5 minutes at normal update speed.*/);
				}
			}
		}
	}
	
	public void placeBlock(int x, int y, int z, Block b, byte data) {
		NormalChunk ch = getChunk(x >> NormalChunk.chunkShift, y >> NormalChunk.chunkShift, z >> NormalChunk.chunkShift);
		if (ch != null) {
			ch.addBlock(b, data, x & NormalChunk.chunkMask, y & NormalChunk.chunkMask, z & NormalChunk.chunkMask, false);
			for (PlaceBlockHandler hand : CubyzRegistries.PLACE_HANDLER_REGISTRY.registered(new PlaceBlockHandler[0])) {
				hand.onBlockPlaced(this, b, x, y, z);
			}
		}
	}
	
	public void drop(ItemStack stack, Vector3d pos, Vector3f dir, float velocity) {
		ItemEntityManager manager = this.getEntityManagerAt((int)pos.x & ~NormalChunk.chunkMask, (int)pos.y & ~NormalChunk.chunkMask, (int)pos.z & ~NormalChunk.chunkMask).itemEntityManager;
		manager.add(pos.x, pos.y, pos.z, dir.x*velocity, dir.y*velocity, dir.z*velocity, stack, 30*300 /*5 minutes at normal update speed.*/);
	}
	
	public void updateBlockData(int x, int y, int z, byte data) {
		NormalChunk ch = getChunk(x >> NormalChunk.chunkShift, y >> NormalChunk.chunkShift, z >> NormalChunk.chunkShift);
		if (ch != null) {
			ch.setBlockData(x & NormalChunk.chunkMask, y & NormalChunk.chunkMask, z & NormalChunk.chunkMask, data);
		}
	}

	public void setGameTime(long time) {
		gameTime = time;
	}

	public long getGameTime() {
		return gameTime;
	}
	
	public void setGameTimeCycle(boolean value)
	{
		doGameTimeCycle = value;
	}
	
	public boolean shouldDoGameTimeCycle()
	{
		return doGameTimeCycle;
	}
	
	public void update() {
		long newTime = System.currentTimeMillis();
		float deltaTime = (newTime - lastUpdateTime)/1000.0f;
		lastUpdateTime = newTime;
		if (deltaTime > 0.3f) {
			Logger.warning("Update time is getting too high. It's already at "+deltaTime+" s!");
			deltaTime = 0.3f;
		}
		
		if (milliTime + 100 < newTime) {
			milliTime += 100;
			if (doGameTimeCycle) gameTime++; // gameTime is measured in 100ms.
		}
		if (milliTime < newTime - 1000) {
			Logger.warning("Behind update schedule by " + (newTime - milliTime) / 1000.0f + "s!");
			milliTime = newTime - 1000; // so we don't accumulate too much time to catch
		}
		int dayCycle = ServerWorld.DAY_CYCLE;
		// Ambient light
		{
			int dayTime = Math.abs((int)(gameTime % dayCycle) - (dayCycle >> 1));
			if(dayTime < (dayCycle >> 2)-(dayCycle >> 4)) {
				ambientLight = 0.1f;
				clearColor.x = clearColor.y = clearColor.z = 0;
			} else if(dayTime > (dayCycle >> 2)+(dayCycle >> 4)) {
				ambientLight = 0.7f;
				clearColor.x = clearColor.y = 0.8f;
				clearColor.z = 1.0f;
			} else {
				//b:
				if(dayTime > (dayCycle >> 2)) {
					clearColor.z = 1.0f*(dayTime-(dayCycle >> 2))/(dayCycle >> 4);
				} else {
					clearColor.z = 0.0f;
				}
				//g:
				if(dayTime > (dayCycle >> 2)+(dayCycle >> 5)) {
					clearColor.y = 0.8f;
				} else if(dayTime > (dayCycle >> 2)-(dayCycle >> 5)) {
					clearColor.y = 0.8f+0.8f*(dayTime-(dayCycle >> 2)-(dayCycle >> 5))/(dayCycle >> 4);
				} else {
					clearColor.y = 0.0f;
				}
				//r:
				if(dayTime > (dayCycle >> 2)) {
					clearColor.x = 0.8f;
				} else {
					clearColor.x = 0.8f+0.8f*(dayTime-(dayCycle >> 2))/(dayCycle >> 4);
				}
				dayTime -= (dayCycle >> 2);
				dayTime <<= 3;
				ambientLight = 0.4f + 0.3f*dayTime/(dayCycle >> 1);
			}
		}
		// Entities
		for (int i = 0; i < entities.size(); i++) {
			Entity en = entities.get(i);
			en.update(deltaTime);
			// Check item entities:
			if(en.getInventory() != null) {
				int x0 = (int)(en.getPosition().x - en.width) & ~NormalChunk.chunkMask;
				int y0 = (int)(en.getPosition().y - en.width) & ~NormalChunk.chunkMask;
				int z0 = (int)(en.getPosition().z - en.width) & ~NormalChunk.chunkMask;
				int x1 = (int)(en.getPosition().x + en.width) & ~NormalChunk.chunkMask;
				int y1 = (int)(en.getPosition().y + en.width) & ~NormalChunk.chunkMask;
				int z1 = (int)(en.getPosition().z + en.width) & ~NormalChunk.chunkMask;
				if(getEntityManagerAt(x0, y0, z0) != null)
					getEntityManagerAt(x0, y0, z0).itemEntityManager.checkEntity(en);
				if(x0 != x1) {
					if(getEntityManagerAt(x1, y0, z0) != null)
						getEntityManagerAt(x1, y0, z0).itemEntityManager.checkEntity(en);
					if(y0 != y1) {
						if(getEntityManagerAt(x0, y1, z0) != null)
							getEntityManagerAt(x0, y1, z0).itemEntityManager.checkEntity(en);
						if(getEntityManagerAt(x1, y1, z0) != null)
							getEntityManagerAt(x1, y1, z0).itemEntityManager.checkEntity(en);
						if(z0 != z1) {
							if(getEntityManagerAt(x0, y0, z1) != null)
								getEntityManagerAt(x0, y0, z1).itemEntityManager.checkEntity(en);
							if(getEntityManagerAt(x1, y0, z1) != null)
								getEntityManagerAt(x1, y0, z1).itemEntityManager.checkEntity(en);
							if(getEntityManagerAt(x0, y1, z1) != null)
								getEntityManagerAt(x0, y1, z1).itemEntityManager.checkEntity(en);
							if(getEntityManagerAt(x1, y1, z1) != null)
								getEntityManagerAt(x1, y1, z1).itemEntityManager.checkEntity(en);
						}
					}
				} else if(y0 != y1) {
					if(getEntityManagerAt(x0, y1, z0) != null)
						getEntityManagerAt(x0, y1, z0).itemEntityManager.checkEntity(en);
					if(z0 != z1) {
						if(getEntityManagerAt(x0, y0, z1) != null)
							getEntityManagerAt(x0, y0, z1).itemEntityManager.checkEntity(en);
						if(getEntityManagerAt(x0, y1, z1) != null)
							getEntityManagerAt(x0, y1, z1).itemEntityManager.checkEntity(en);
					}
				} else if(z0 != z1) {
					if(getEntityManagerAt(x0, y0, z1) != null)
						getEntityManagerAt(x0, y0, z1).itemEntityManager.checkEntity(en);
				}
			}
		}
		// Item Entities
		for(int i = 0; i < entityManagers.length; i++) {
			entityManagers[i].itemEntityManager.update(deltaTime);
		}
		// Block Entities
		for(MetaChunk chunk : metaChunks.values()) {
			chunk.updateBlockEntities();
		}
		
		// Liquids
		if (gameTime % 3 == 0) {
			//Profiler.startProfiling();
			for(MetaChunk chunk : metaChunks.values()) {
				chunk.liquidUpdate();
			}
			//Profiler.printProfileTime("liquid-update");
		}
	}

	public void queueChunk(ChunkData ch) {
		try {
			loadList.put(ch);
		} catch (InterruptedException e) {
			System.err.println("Interrupted while queuing chunk. This is unexpected.");
		}
	}
	
	public void unQueueChunk(ChunkData ch) {
		loadList.remove(ch);
	}
	
	public int getChunkQueueSize() {
		return loadList.size();
	}
	
	public void seek(int x, int y, int z, int renderDistance, int regionRenderDistance) {
		int xOld = x;
		int yOld = y;
		int zOld = z;
		// Care about the Regions:
		regionRenderDistance = (regionRenderDistance + MapFragment.MAP_SIZE - 1)/MapFragment.MAP_SIZE;
		int local = x & MapFragment.MAP_MASK;
		x >>= MapFragment.MAP_SHIFT;
		x += regionRenderDistance;
		if(local >= MapFragment.MAP_SIZE/2)
			x++;
		local = z & MapFragment.MAP_MASK;
		z >>= MapFragment.MAP_SHIFT;
		z += regionRenderDistance;
		if(local >= MapFragment.MAP_SIZE/2)
			z++;
		int regionDRD = regionRenderDistance << 1;
		if(x != lastRegX || z != lastRegZ || regionDRD != regDRD) {
			MapFragment[] newMaps = new MapFragment[regionDRD*regionDRD];
			// Go through the old regions and put them in the new array:
			for(int i = 0; i < maps.length; i++) {
				if(maps[i] != null) {
					int dx = (maps[i].wx >> MapFragment.MAP_SHIFT) - (x-regionDRD);
					int dz = (maps[i].wz >> MapFragment.MAP_SHIFT) - (z-regionDRD);
					if(dx >= 0 && dx < regionDRD && dz >= 0 && dz < regionDRD) {
						int index = dx*regionDRD + dz;
						newMaps[index] = maps[i];
					} else {
						maps[i].mapIO.saveData();
					}
				}
			}
			maps = newMaps;
			lastRegX = x;
			lastRegZ = z;
			regDRD = regionDRD;
		}
		
		// Care about the metaChunks:
		if(xOld != lastX || yOld != lastY || zOld != lastZ) {
			ArrayList<NormalChunk> chunkList = new ArrayList<>();
			ArrayList<ChunkEntityManager> managers = new ArrayList<>();
			HashMap<HashMapKey3D, MetaChunk> newMetaChunks = new HashMap<HashMapKey3D, MetaChunk>();
			int metaRenderDistance = (int)Math.ceil(renderDistance/(float)(MetaChunk.metaChunkSize*NormalChunk.chunkSize));
			x = xOld;
			y = yOld;
			z = zOld;
			int x0 = x/(MetaChunk.metaChunkSize*NormalChunk.chunkSize);
			int y0 = y/(MetaChunk.metaChunkSize*NormalChunk.chunkSize);
			int z0 = z/(MetaChunk.metaChunkSize*NormalChunk.chunkSize);
			for(int metaX = x0 - metaRenderDistance; metaX <= x0 + metaRenderDistance + 1; metaX++) {
				for(int metaY = y0 - metaRenderDistance; metaY <= y0 + metaRenderDistance + 1; metaY++) {
					for(int metaZ = z0 - metaRenderDistance; metaZ <= z0 + metaRenderDistance + 1; metaZ++) {
						int xReal = metaX;
						int zReal = metaZ;
						HashMapKey3D key = new HashMapKey3D(xReal, metaY, zReal);
						// Check if it already exists:
						MetaChunk metaChunk = metaChunks.get(key);
						if(metaChunk == null) {
							metaChunk = new MetaChunk(xReal*(MetaChunk.metaChunkSize*NormalChunk.chunkSize), metaY*(MetaChunk.metaChunkSize*NormalChunk.chunkSize), zReal*(MetaChunk.metaChunkSize*NormalChunk.chunkSize), this);
						}
						newMetaChunks.put(key, metaChunk);
						metaChunk.updatePlayer(xOld, yOld, zOld, renderDistance, Settings.entityDistance, chunkList, managers);
					}
				}
			}
			chunks = chunkList.toArray(new NormalChunk[0]);
			entityManagers = managers.toArray(new ChunkEntityManager[0]);
			metaChunks = newMetaChunks;
			lastX = xOld;
			lastY = yOld;
			lastZ = zOld;
		}
	}

	public MapFragment getMapFragment(int wx, int wz, int voxelSize) {
		wx &= ~MapFragment.MAP_MASK;
		wz &= ~MapFragment.MAP_MASK;
		int x = wx >> MapFragment.MAP_SHIFT;
		int z = wz >> MapFragment.MAP_SHIFT;
		// Test if the chunk can be found in the list of visible chunks:
		int dx = x - (lastRegX - regDRD/2) + regDRD/2;
		int dz = z - (lastRegZ - regDRD/2) + regDRD/2;
		if(dx >= 0 && dx < regDRD && dz >= 0 && dz < regDRD) {
			int index = dx*regDRD + dz;
			synchronized(maps) {
				MapFragment ret = maps[index];
				
				if (ret != null) {
					ret.ensureResolution(getSeed(), registries, voxelSize);
					return ret;
				} else {
					MapFragment map = new MapFragment(wx, wz, seed, this, registries, wio, voxelSize);
					maps[index] = map;
					return map;
				}
			}
		}
		return new MapFragment(wx, wz, seed, this, registries, wio, voxelSize);
	}
	
	public MapFragment getNoGenerateRegion(int wx, int wy) {
		for(MapFragment map : maps) {
			if(map.wx == wx && map.wz == wy) {
				return map;
			}
		}
		return null;
	}

	/**
	 * Only for internal use. Generates a reduced chunk at a given location, or if possible gets it from the cache.
	 * @param wx
	 * @param wy
	 * @param wz
	 * @param voxelSize
	 * @return
	 */
	public ReducedChunk getOrGenerateReducedChunk(int wx, int wy, int wz, int voxelSize) {
		ChunkData data = new ChunkData(wx, wy, wz, voxelSize);
		int hash = data.hashCode() & CHUNK_CACHE_MASK;
		ReducedChunk res = reducedChunkCache.find(data, hash);
		if(res != null) return res;
		synchronized(reducedChunkCache.cache[hash]) {
			res = reducedChunkCache.find(data, hash);
			if(res != null) return res;
			// Generate a new chunk:
			res = new ReducedChunk(wx, wy, wz, CubyzMath.binaryLog(voxelSize));
			res.generateFrom(generator);
			reducedChunkCache.addToCache(res, hash);
		}
		return res;
	}
	
	public MetaChunk getMetaChunk(int cx, int cy, int cz) {
		// Test if the metachunk exists:
		int metaX = cx >> (MetaChunk.metaChunkShift);
		int metaY = cy >> (MetaChunk.metaChunkShift);
		int metaZ = cz >> (MetaChunk.metaChunkShift);
		HashMapKey3D key = new HashMapKey3D(metaX, metaY, metaZ);
		return metaChunks.get(key);
	}
	
	public NormalChunk getChunk(int cx, int cy, int cz) {
		MetaChunk meta = getMetaChunk(cx, cy, cz);
		if(meta != null) {
			return meta.getChunk(cx, cy, cz);
		}
		return null;
	}

	public ChunkEntityManager getEntityManagerAt(int wx, int wy, int wz) {
		int cx = wx >> NormalChunk.chunkShift;
		int cy = wy >> NormalChunk.chunkShift;
		int cz = wz >> NormalChunk.chunkShift;
		MetaChunk meta = getMetaChunk(cx, cy, cz);
		if(meta != null) {
			return meta.getEntityManager(cx, cy, cz);
		}
		return null;
	}
	
	public ChunkEntityManager[] getEntityManagers() {
		return entityManagers;
	}

	public Block getBlock(int x, int y, int z) {
		NormalChunk ch = getChunk(x >> NormalChunk.chunkShift, y >> NormalChunk.chunkShift, z >> NormalChunk.chunkShift);
		if (ch != null && ch.isGenerated()) {
			Block b = ch.getBlock(x & NormalChunk.chunkMask, y & NormalChunk.chunkMask, z & NormalChunk.chunkMask);
			return b;
		} else {
			return null;
		}
	}
	
	public byte getBlockData(int x, int y, int z) {
		NormalChunk ch = getChunk(x >> NormalChunk.chunkShift, y >> NormalChunk.chunkShift, z >> NormalChunk.chunkShift);
		if (ch != null && ch.isGenerated()) {
			return ch.getBlockData(x & NormalChunk.chunkMask, y & NormalChunk.chunkMask, z & NormalChunk.chunkMask);
		} else {
			return 0;
		}
	}
	
	public long getSeed() {
		return seed;
	}
	
	public float getGlobalLighting() {
		return ambientLight;
	}

	public Vector4f getClearColor() {
		return clearColor;
	}

	public String getName() {
		return name;
	}

	public BlockEntity getBlockEntity(int x, int y, int z) {
		/*BlockInstance bi = getBlockInstance(x, y, z);
		Chunk ck = _getNoGenerateChunk(bi.getX() >> NormalChunk.chunkShift, bi.getZ() >> NormalChunk.chunkShift);
		return ck.blockEntities().get(bi);*/
		return null; // TODO: Work on BlockEntities!
	}
	
	public ArrayList<CustomBlock> getCustomBlocks() {
		return customBlocks;
	}

	public NormalChunk[] getChunks() {
		return chunks;
	}

	public Block[] getBlocks() {
		return blocks;
	}
	
	public Entity[] getEntities() {
		return entities.toArray(new Entity[entities.size()]);
	}
	
	public int getHeight(int wx, int wz) {
		return (int)getMapFragment(wx, wz, 1).getHeight(wx, wz);
	}

	public void cleanup() {
		// Be sure to dereference and finalize the maximum of things
		try {
			forceSave();
			
			for (Thread thread : generatorThreads) {
				thread.interrupt();
				thread.join();
			}
			generatorThreads = new ArrayList<>();
			
			metaChunks = null;
		} catch (Exception e) {
			Logger.error(e);
		}
	}

	public CurrentWorldRegistries getCurrentRegistries() {
		return registries;
	}

	public Biome getBiome(int wx, int wz) {
		MapFragment reg = getMapFragment(wx, wz, 1);
		return reg.getBiome(wx, wz);
	}

	public int getLight(int x, int y, int z, Vector3f sunLight, boolean easyLighting) {
		NormalChunk ch = getChunk(x >> NormalChunk.chunkShift, y >> NormalChunk.chunkShift, z >> NormalChunk.chunkShift);
		if(ch == null || !ch.isLoaded() || !easyLighting)
			return 0xffffffff;
		return ch.getLight(x & NormalChunk.chunkMask, y & NormalChunk.chunkMask, z & NormalChunk.chunkMask);
	}

	public void getLight(int x, int y, int z, int[] array) {
		Block block = getBlock(x, y, z);
		if(block == null) return;
		int selfLight = block.getLight();
		x--;
		y--;
		z--;
		for(int ix = 0; ix < 3; ix++) {
			for(int iy = 0; iy < 3; iy++) {
				for(int iz = 0; iz < 3; iz++) {
					array[ix + iy*3 + iz*9] = getLight(x+ix, y+iy, z+iz, selfLight);
				}
			}
		}
	}
	
	private int getLight(int x, int y, int z, int minLight) {
		NormalChunk ch = getChunk(x >> NormalChunk.chunkShift, y >> NormalChunk.chunkShift, z >> NormalChunk.chunkShift);
		if(ch == null || !ch.isLoaded())
			return 0xff000000;
		int light = ch.getLight(x & NormalChunk.chunkMask, y & NormalChunk.chunkMask, z & NormalChunk.chunkMask);
		// Make sure all light channels are at least as big as the minimum:
		if((light & 0xff000000) >>> 24 < (minLight & 0xff000000) >>> 24) light = (light & 0x00ffffff) | (minLight & 0xff000000);
		if((light & 0x00ff0000) < (minLight & 0x00ff0000)) light = (light & 0xff00ffff) | (minLight & 0x00ff0000);
		if((light & 0x0000ff00) < (minLight & 0x0000ff00)) light = (light & 0xffff00ff) | (minLight & 0x0000ff00);
		if((light & 0x000000ff) < (minLight & 0x000000ff)) light = (light & 0xffffff00) | (minLight & 0x000000ff);
		return light;
	}
}
