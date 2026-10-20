package rs117.hd.scene.weather;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Weather types ordered from fair to foul, each owning a share of the latent range [0, 1) proportional
 * to its weight. Because the timeline's latent value is uniformly distributed over time, each weight is
 * exactly the long-run share of time the type is active.
 */
public final class ClimateLadder {
	public final WeatherType[] types;
	public final double[] weights;
	/** Bin edges: edges[0] = 0, edges[i + 1] = edges[i] + weights[i] / totalWeight, edges[length] = 1. */
	public final double[] edges;
	public final double totalWeight;

	private ClimateLadder(WeatherType[] types, double[] weights) {
		this.types = types;
		this.weights = weights;
		double total = 0;
		for (double w : weights)
			total += w;
		totalWeight = total;
		edges = new double[types.length + 1];
		for (int i = 0; i < types.length; i++)
			edges[i + 1] = edges[i] + weights[i] / total;
		edges[types.length] = 1;
	}

	public static ClimateLadder of(WeatherType[] types, double[] weights) {
		if (types.length == 0 || types.length != weights.length)
			throw new IllegalArgumentException("A ladder needs one positive weight per type");
		for (double w : weights)
			if (!(w > 0) || !Double.isFinite(w))
				throw new IllegalArgumentException("Ladder weights must be positive");
		return new ClimateLadder(types.clone(), weights.clone());
	}

	@Nullable
	static ClimateLadder parse(JsonElement element, Map<String, WeatherType> types, String ctx, List<String> errors) {
		if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() == 0) {
			errors.add(ctx + ": a ladder must be a non-empty array of [TYPE, weight] pairs");
			return null;
		}
		JsonArray arr = element.getAsJsonArray();
		WeatherType[] ladderTypes = new WeatherType[arr.size()];
		double[] weights = new double[arr.size()];
		var seen = new HashSet<WeatherType>();
		boolean valid = true;
		for (int i = 0; i < arr.size(); i++) {
			JsonElement entry = arr.get(i);
			if (!entry.isJsonArray() || entry.getAsJsonArray().size() != 2
				|| !WeatherJson.isString(entry.getAsJsonArray().get(0))
				|| !WeatherJson.isNumber(entry.getAsJsonArray().get(1))) {
				errors.add(ctx + "[" + i + "]: expected [TYPE, weight]");
				valid = false;
				continue;
			}
			String typeName = entry.getAsJsonArray().get(0).getAsString();
			double weight = entry.getAsJsonArray().get(1).getAsDouble();
			WeatherType type = types.get(typeName);
			if (type == null) {
				errors.add(ctx + "[" + i + "]: unknown weather type '" + typeName + "'");
				valid = false;
				continue;
			}
			if (!(weight > 0) || !Double.isFinite(weight)) {
				errors.add(ctx + "[" + i + "]: weight must be positive");
				valid = false;
			}
			if (!seen.add(type)) {
				errors.add(ctx + ": '" + typeName + "' appears more than once");
				valid = false;
			}
			ladderTypes[i] = type;
			weights[i] = weight;
		}
		return valid ? new ClimateLadder(ladderTypes, weights) : null;
	}

	/** Index of the bin containing the latent value s. */
	public int bin(double s) {
		int lo = 0, hi = types.length - 1;
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (edges[mid] <= s) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		return lo;
	}

	/** Position of s inside the given bin, from 0 at the fair end to 1 at the foul end. */
	public double intensity(double s, int bin) {
		double width = edges[bin + 1] - edges[bin];
		if (width <= 0)
			return 0;
		return Math.max(0, Math.min(1, (s - edges[bin]) / width));
	}

	public WeatherType typeAt(double s) {
		return types[bin(s)];
	}

	public boolean contains(WeatherType type) {
		for (var t : types)
			if (t == type)
				return true;
		return false;
	}

	/**
	 * Ensures severities never decrease, and that any two types further apart in severity than maxStep are
	 * separated by at least {@code volatility} of the latent range. Since the latent moves by at most the
	 * volatility at each segment boundary, the weather can then never jump straight between them.
	 */
	public void validate(double volatility, int maxStep, String ctx, List<String> errors) {
		for (int i = 1; i < types.length; i++) {
			if (types[i].severity < types[i - 1].severity) {
				errors.add(String.format(Locale.ROOT,
					"%s: severity must not decrease from %s (%d) to %s (%d); order ladders from fair to foul",
					ctx, types[i - 1].name, types[i - 1].severity, types[i].name, types[i].severity));
			}
		}

		for (int i = 0; i < types.length; i++) {
			for (int j = i + 1; j < types.length; j++) {
				if (Math.abs(types[j].severity - types[i].severity) <= maxStep)
					continue;
				double between = edges[j] - edges[i + 1];
				if (between + 1e-9 < volatility) {
					errors.add(String.format(Locale.ROOT,
						"%s: %s and %s are more than %d severity steps apart, so the weights between them must total at least "
						+ "%.2f of %.2f (the timeline volatility), but they total %.2f",
						ctx, types[i].name, types[j].name, maxStep,
						volatility * totalWeight, totalWeight, between * totalWeight));
				}
			}
		}
	}
}
