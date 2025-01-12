package cubyz.world.save;

import java.io.IOException;

import cubyz.Logger;
import cubyz.utils.json.JsonObject;
import cubyz.world.ServerWorld;
import cubyz.world.entity.Entity;
import cubyz.world.entity.EntityType;

public class EntityIO {	
	public static Entity loadEntity(JsonObject json, ServerWorld world) throws IOException {
		String id = json.getString("id", "");
		Entity ent;
		EntityType type = world.getCurrentRegistries().entityRegistry.getByID(id);
		if (type == null) {
			Logger.warning("Could not load entity with id " + id.toString());
			return null;
		}
		ent = type.newEntity(world);
		ent.loadFrom(json);
		return ent;
	}
	
}
