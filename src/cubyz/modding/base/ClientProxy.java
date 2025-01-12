package cubyz.modding.base;

import cubyz.api.ClientRegistries;
import cubyz.api.CubyzRegistries;
import cubyz.api.Registry;
import cubyz.gui.CreativeGUI;
import cubyz.gui.WorkbenchGUI;
import cubyz.rendering.entity_models.*;
import cubyz.rendering.models.CubeModel;
import cubyz.rendering.rotation.*;
import cubyz.world.blocks.RotationMode;
import cubyz.world.entity.EntityModel;

/**
 * Registers objects that are only available on the client.
 */

public class ClientProxy extends CommonProxy {

	public void init() {
		super.init();
		ClientRegistries.GUIS.register(new WorkbenchGUI());
		ClientRegistries.GUIS.register(new CreativeGUI());
	}
	
	public void preInit() {
		registerRotationModes(CubyzRegistries.ROTATION_MODE_REGISTRY);
		registerEntityModels(CubyzRegistries.ENTITY_MODEL_REGISTRY);
		CubeModel.registerCubeModels();
	}

	private void registerRotationModes(Registry<RotationMode> reg) {
		reg.register(new NoRotation());
		reg.register(new TorchRotation());
		reg.register(new LogRotation());
		reg.register(new StackableRotation());
		reg.register(new FenceRotation());
	}

	private void registerEntityModels(Registry<EntityModel> reg) {
		reg.register(new Quadruped());
	}
	
}
