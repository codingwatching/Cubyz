package cubyz.gui.components;

import cubyz.gui.Component;
import cubyz.gui.input.Mouse;
import cubyz.rendering.Graphics;

public class ScrollingContainer extends Container {

	int maxY = 0;
	int scrollY = 0;
	int scrollBarWidth = 20;
	
	int mPickY = -1;
	
	@Override
	public void render(int x, int y) {
		maxY = 0;
		for (Component child : childrens) {
			maxY = Math.max(maxY, child.getY()+child.getHeight());
			child.setY(child.getY() - scrollY);
			child.render();
			child.setY(child.getY() + scrollY);
		}
		if (maxY > height) {
			Graphics.setColor(0x000000);
			Graphics.fillRect(x + width - scrollBarWidth, y, scrollBarWidth, height);
			Graphics.setColor(0xffffff);
			Graphics.fillRect(x + width - scrollBarWidth, y+(scrollY/((float) maxY / height)), scrollBarWidth, 10);
			if (mPickY == -1) {
				if (Mouse.getX() > x + width - scrollBarWidth && Mouse.getX() < x + width) {
					if (Mouse.getY() > y && Mouse.getY() < height) {
						if (Mouse.isLeftButtonPressed()) {
							mPickY = (int) Mouse.getY();
						}
					}
				}
			} else {
				if (Mouse.isLeftButtonPressed()) {
					scrollY = (int) Math.min(maxY, Mouse.getY()*(maxY/height) + mPickY);
				} else {
					mPickY = -1;
				}
			}
			scrollY += -Mouse.getScrollOffset() * 40;
			if (scrollY < 0) scrollY = 0;
		} else {
			scrollY = 0;
		}
		scrollY = Math.min(maxY, scrollY);
	}
	
}