package cubyz.gui;

import org.joml.Vector3f;

import cubyz.api.Resource;
import cubyz.client.Cubyz;
import cubyz.gui.components.InventorySlot;
import cubyz.gui.components.Label;
import cubyz.gui.input.Mouse;
import cubyz.rendering.Graphics;
import cubyz.rendering.Texture;
import cubyz.rendering.Window;
import cubyz.world.items.Item;
import cubyz.world.items.ItemStack;

/**
 * A class containing common functionality from all Inventory GUIs(tooltips, inventory slot movement, inventory slot drawing).
 */

public abstract class GeneralInventory extends MenuGUI {
	protected InventorySlot inv [] = null;
	
	/** ItemStack carried by the mouse.*/
	protected ItemStack carried = new ItemStack();
	private Label num;
	
	protected int width, height;
	
	public GeneralInventory(Resource id) {
		super(id);
	}

	@Override
	public void close() {
		 // Place the last stack carried by the mouse in an empty slot.
		if(!carried.empty()) {
			carried.setAmount(Cubyz.player.getInventory().addItem(carried.getItem(), carried.getAmount()));
			if(!carried.empty()) {
				Cubyz.world.drop(carried, Cubyz.player.getPosition(), new Vector3f(), 0);
			}
		}
	}

	@Override
	public void init() {
		Mouse.setGrabbed(false);
		num = new Label();
		num.setTextAlign(Component.ALIGN_CENTER);
		positionSlots();
	}

	@Override
	public void render() {
		Graphics.setColor(0xDFDFDF);
		Graphics.fillRect(Window.getWidth()/2f-width/2f, Window.getHeight()-height, width, height);
		Graphics.setColor(0xFFFFFF);
		for(int i = 0; i < inv.length; i++) {
			inv[i].render();
		}
		Graphics.setColor(0x000000);
		// Check if the mouse takes up a new ItemStack/sets one down.
		mouseAction();
		
		// Draw the stack carried by the mouse:
		Item item = carried.getItem();
		if(item != null) {
			if(item.getImage() == null) {
				item.setImage(Texture.loadFromFile(item.getTexture()));
			}
			int x = (int)Mouse.getCurrentPos().x;
			int y = (int)Mouse.getCurrentPos().y;
			Graphics.setColor(0xFFFFFF);
			Graphics.drawImage(item.getImage(), x - 32, y - 32, 64, 64);
			Graphics.setColor(0x000000);
			num.setText("" + carried.getAmount());
			num.setPosition(x+50-32, y+48-32, Component.ALIGN_TOP_LEFT);
			num.render();
		}
		// Draw tooltips, when the nothing is carried.
		if(item == null) {
			for(int i = 0; i < inv.length; i++) { // tooltips
				inv[i].drawTooltip(Window.getWidth() / 2, Window.getHeight());
			}
		}
	}

	@Override
	public boolean doesPauseGame() {
		return false;
	}
	
	protected abstract void positionSlots();
	
	protected abstract void mouseAction();
}
