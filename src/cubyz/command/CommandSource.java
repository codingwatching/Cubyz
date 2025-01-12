package cubyz.command;

import cubyz.world.ServerWorld;

/**
 * Base interface for all entities that can execute commands.
 * @author zenith391
 */

public interface CommandSource {
	
	public void feedback(String feedback);
	public ServerWorld getWorld();
	
}
