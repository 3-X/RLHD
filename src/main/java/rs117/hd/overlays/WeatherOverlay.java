package rs117.hd.overlays;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import rs117.hd.HdPlugin;
import rs117.hd.scene.WeatherManager;
import rs117.hd.scene.weather.WeatherCommands;

/**
 * Developer overlay showing the weather controller's state. Toggle with {@code ::117hd weather} or Ctrl+F8
 * during development.
 */
@Singleton
public class WeatherOverlay extends OverlayPanel {
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private WeatherManager weatherManager;

	@Inject
	public WeatherOverlay(HdPlugin plugin) {
		super(plugin);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.TOP_LEFT);
		panelComponent.setPreferredSize(new Dimension(300, 200));
	}

	public void setActive(boolean activate) {
		if (activate) {
			overlayManager.add(this);
		} else {
			overlayManager.remove(this);
		}
	}

	@Override
	public Dimension render(Graphics2D g) {
		var children = panelComponent.getChildren();
		boolean overrides = weatherManager.getOverrides().any() ||
			!weatherManager.getClock().isRealTime(System.currentTimeMillis());
		children.add(TitleComponent.builder()
			.text(overrides ? "Weather [overrides]" : "Weather")
			.color(overrides ? Color.ORANGE : Color.WHITE)
			.build());
		for (String[] row : WeatherCommands.describe(weatherManager)) {
			children.add(LineComponent.builder()
				.left(row[0] + ":")
				.right(row[1])
				.build());
		}
		return super.render(g);
	}
}
