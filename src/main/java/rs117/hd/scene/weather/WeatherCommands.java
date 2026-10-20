package rs117.hd.scene.weather;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.scene.WeatherManager;

import static rs117.hd.scene.weather.WeatherConstants.*;
import static rs117.hd.utils.MathUtils.*;

/**
 * Handles {@code ::117hd weather ...} developer commands and formats the weather debug overlay.
 */
public final class WeatherCommands {
	private WeatherCommands() {}

	public static final String USAGE =
		"Usage: ::117hd weather [info | forecast [hours] | force <TYPE> [TO_TYPE] [intensity] [now] | force auto | next [TYPE] | "
		+ "climate <NAME|auto> | season <SUMMER|AUTUMN|WINTER|auto> | time <+-N[s|m|h|d]|reset> | speed <x|reset> | "
		+ "wind <degrees> <strength> [speed] | wind auto | stats [CLIMATE] [SEASON] [days] | strip | off | on | reset]";

	private static final Pattern DURATION = Pattern.compile("^([+-]?\\d+(?:\\.\\d+)?)([smhd]?)$");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);

	/**
	 * @param args the command arguments, starting with "weather"
	 * @return chat lines to show the user
	 */
	public static List<String> handle(WeatherManager wm, String[] args) {
		List<String> out = new ArrayList<>();
		if (args.length < 2) {
			out.add(USAGE);
			return out;
		}
		String action = args[1].toLowerCase(Locale.ROOT);
		WeatherData data = wm.getData();
		try {
			switch (action) {
				case "info":
					out.add(info(wm));
					break;
				case "forecast":
					forecast(wm, args, out);
					break;
				case "force":
					force(wm, data, args, out);
					break;
				case "next": {
					WeatherType type = null;
					if (args.length >= 3) {
						type = data == null ? null : data.type(args[2].toUpperCase(Locale.ROOT));
						if (type == null) {
							out.add("Unknown weather type: " + args[2] + typeList(data));
							break;
						}
					}
					out.add(wm.jumpToNext(type));
					break;
				}
				case "climate":
					if (args.length < 3) {
						out.add("Usage: ::117hd weather climate <NAME|auto>" + climateList(data));
					} else if (args[2].equalsIgnoreCase("auto")) {
						wm.forceClimate(null);
						out.add("Following the bound climate again");
					} else if (data == null || data.climate(args[2].toUpperCase(Locale.ROOT)) == null) {
						out.add("Unknown climate: " + args[2] + climateList(data));
					} else {
						wm.forceClimate(args[2].toUpperCase(Locale.ROOT));
						out.add("Forcing the climate " + args[2].toUpperCase(Locale.ROOT) + " (exposure gating still applies)");
					}
					break;
				case "season":
					if (args.length < 3) {
						out.add("Usage: ::117hd weather season <SUMMER|AUTUMN|WINTER|auto>");
					} else if (args[2].equalsIgnoreCase("auto")) {
						wm.forceSeason(null);
						out.add("Following the seasonal theme again");
					} else {
						SeasonalTheme season = parseSeason(args[2]);
						if (season == null) {
							out.add("Unknown season: " + args[2] + ". Use SUMMER, AUTUMN or WINTER.");
						} else {
							wm.forceSeason(season);
							out.add("Forcing the weather season " + season + " (ground tiles are unaffected)");
						}
					}
					break;
				case "time":
					if (args.length < 3) {
						out.add("Usage: ::117hd weather time <+-N[s|m|h|d]|reset>");
					} else if (args[2].equalsIgnoreCase("reset")) {
						wm.resetClock();
						out.add("The weather clock follows real time again");
					} else {
						Long ms = parseDuration(args[2]);
						if (ms == null) {
							out.add("Invalid duration: " + args[2] + ". Examples: +90m, -2h, 1d, 30s (a bare number means minutes).");
						} else {
							wm.shiftClock(ms);
							out.add("Shifted the weather clock by " + WeatherManager.formatDuration(ms));
						}
					}
					break;
				case "speed":
					if (args.length < 3) {
						out.add("Usage: ::117hd weather speed <x|reset>");
					} else if (args[2].equalsIgnoreCase("reset")) {
						wm.setClockScale(1);
						out.add("The weather clock runs at real-time speed again (any accumulated offset remains; use 'time reset' to clear it)");
					} else {
						Double scale = parseFinite(args[2]);
						if (scale == null || scale < 0) {
							out.add("The speed must be a number between 0 and " + (int) WeatherClock.MAX_SCALE);
						} else {
							wm.setClockScale(scale);
							out.add(String.format(Locale.ROOT, "The weather clock now runs at x%.2f", wm.getClock().getScale()));
						}
					}
					break;
				case "wind":
					wind(wm, args, out);
					break;
				case "strip":
					out.addAll(describeStrip(wm));
					break;
				case "stats":
					stats(wm, data, args, out);
					break;
				case "off":
					wm.setEnabled(false);
					out.add("Weather disabled: publishing the environment's wind unchanged");
					break;
				case "on":
					wm.setEnabled(true);
					out.add("Weather enabled");
					break;
				case "reset":
					wm.resetOverrides();
					out.add("Cleared all weather overrides and reset the weather clock");
					break;
				default:
					out.add(USAGE);
					break;
			}
		} catch (RuntimeException ex) {
			out.add("Weather command failed: " + ex);
		}
		return out;
	}

	private static void force(WeatherManager wm, @Nullable WeatherData data, String[] args, List<String> out) {
		if (data == null) {
			out.add("No weather data is loaded");
			return;
		}
		if (args.length < 3) {
			out.add("Usage: ::117hd weather force <TYPE> [TO_TYPE] [intensity] [now] | force auto" + typeList(data));
			return;
		}
		if (args[2].equalsIgnoreCase("auto")) {
			wm.clearForcedType();
			out.add("Natural weather resumes, cross-fading over " + (int) CLIMATE_BLEND_SECONDS + " seconds");
			return;
		}

		List<WeatherType> types = new ArrayList<>();
		float intensity = Float.NaN;
		boolean now = false;
		for (int i = 2; i < args.length; i++) {
			String arg = args[i];
			if (arg.equalsIgnoreCase("now")) {
				now = true;
				continue;
			}
			Double number = parseFinite(arg);
			if (number != null) {
				if (number < 0 || number > 1) {
					out.add("The intensity must be between 0 and 1");
					return;
				}
				intensity = number.floatValue();
				continue;
			}
			WeatherType type = data.type(arg.toUpperCase(Locale.ROOT));
			if (type == null) {
				out.add("Unknown weather type: " + arg + typeList(data));
				return;
			}
			types.add(type);
		}
		if (types.isEmpty() || types.size() > 2) {
			out.add("Specify one weather type, or a starting type followed by the type to roll in");
			return;
		}

		WeatherType target = types.get(types.size() - 1);
		WeatherType from = types.size() == 2 ? types.get(0) : null;
		var state = wm.getState();
		if (state.climate != null && state.climate.conflictsWith(target))
			out.add("Note: " + state.climate.name + " normally forbids parts of " + target.name + "; forced weather ignores that");

		if (now) {
			if (from != null) {
				out.add("'now' can't be combined with a starting type");
				return;
			}
			wm.forceNow(target, intensity);
			out.add("Forcing " + target.name + " now, cross-fading over " + (int) CLIMATE_BLEND_SECONDS + " seconds");
		} else {
			out.add(wm.forceRollIn(target, from, intensity));
		}
	}

	private static void wind(WeatherManager wm, String[] args, List<String> out) {
		if (args.length >= 3 && args[2].equalsIgnoreCase("auto")) {
			wm.clearWindOverride();
			out.add("The weather controls the wind again");
			return;
		}
		if (args.length < 4) {
			out.add("Usage: ::117hd weather wind <degrees> <strength> [speed] | wind auto. Degrees: 0 = blowing east, 90 = north");
			return;
		}
		Double degrees = parseFinite(args[2]);
		Double strength = parseFinite(args[3]);
		Double speed = args.length >= 5 ? parseFinite(args[4]) : Double.valueOf(15);
		final double maxStrength = 3 * WIND_STRENGTH_MAX, maxSpeed = 3 * WIND_SPEED_MAX;
		if (degrees == null || strength == null || speed == null ||
			strength < 0 || strength > maxStrength || speed < 0 || speed > maxSpeed) {
			out.add(String.format(Locale.ROOT, "The angle must be a number, the strength within [0, %.0f] and the speed within [0, %.0f]",
				maxStrength, maxSpeed));
			return;
		}
		degrees %= 360;
		wm.overrideWind(degrees.floatValue(), strength.floatValue(), speed.floatValue());
		out.add(String.format(Locale.ROOT, "Wind overridden: %.0f degrees, strength %.1f, speed %.1f", degrees, strength, speed));
	}

	private static void forecast(WeatherManager wm, String[] args, List<String> out) {
		int hours = 12;
		if (args.length >= 3) {
			Double h = parseFinite(args[2]);
			if (h == null || h <= 0) {
				out.add("Usage: ::117hd weather forecast [hours]");
				return;
			}
			hours = (int) Math.min(FORECAST_MAX_HOURS, Math.ceil(h));
		}
		var state = wm.getState();
		var spells = wm.forecast(state.worldPoint[0], state.worldPoint[1], state.worldPoint[2], hours * 3_600_000L);
		if (spells.isEmpty()) {
			out.add("No forecast available here");
			return;
		}
		out.add("Forecast for the next " + hours + "h at " + state.worldPoint[0] + ", " + state.worldPoint[1] + " (weather clock, UTC):");
		for (var spell : spells) {
			out.add(String.format(Locale.ROOT, "%s  %s (intensity %.2f-%.2f, %.2f to %.2f) for %s",
				DATE_TIME.format(Instant.ofEpochMilli(spell.startClock)),
				spell.type.name,
				spell.intensityMin,
				spell.intensityMax,
				spell.intensityFrom,
				spell.intensityTo,
				WeatherManager.formatDuration(spell.endClock - spell.startClock)));
		}
	}

	private static void stats(WeatherManager wm, @Nullable WeatherData data, String[] args, List<String> out) {
		if (data == null) {
			out.add("No weather data is loaded");
			return;
		}
		var state = wm.getState();
		Climate climate = state.climate != null ? state.climate : data.defaultClimate;
		SeasonalTheme season = wm.getSeason();
		int days = 365;
		for (int i = 2; i < args.length; i++) {
			String arg = args[i].toUpperCase(Locale.ROOT);
			SeasonalTheme s = parseSeason(arg);
			Double number = parseFinite(arg);
			if (s != null) {
				season = s;
			} else if (number != null && number >= 1) {
				days = (int) Math.min(3650, number);
			} else if (data.climate(arg) != null) {
				climate = data.climate(arg);
			} else {
				out.add("Unknown climate, season or day count: " + args[i] + climateList(data));
				return;
			}
		}
		out.addAll(simulate(data, climate, season, days));
	}

	/** Simulate a climate's weather over many days, for tuning ladders. */
	public static List<String> simulate(WeatherData data, Climate climate, SeasonalTheme season, int days) {
		var timeline = new WeatherTimeline(data.timeline.settings);
		var ladder = climate.ladderFor(season);
		long start = Math.floorDiv(System.currentTimeMillis(), EPOCH_MS) * EPOCH_MS;
		long end = start + days * EPOCH_MS;

		Map<WeatherType, Long> time = new LinkedHashMap<>();
		Map<WeatherType, long[]> spells = new LinkedHashMap<>(); // count, total length
		for (var type : ladder.types) {
			time.put(type, 0L);
			spells.put(type, new long[2]);
		}
		int changes = 0, maxStep = 0;
		var segment = timeline.segmentAt(start);
		WeatherType spellType = ladder.typeAt(segment.latent);
		long spellStart = start;
		while (segment.start < end) {
			WeatherType type = ladder.typeAt(segment.latent);
			long from = Math.max(segment.start, start), to = Math.min(segment.end, end);
			time.merge(type, to - from, Long::sum);
			if (type != spellType) {
				var s = spells.get(spellType);
				s[0]++;
				s[1] += from - spellStart;
				changes++;
				maxStep = Math.max(maxStep, Math.abs(type.severity - spellType.severity));
				spellType = type;
				spellStart = from;
			}
			segment = timeline.next(segment);
		}
		var last = spells.get(spellType);
		last[0]++;
		last[1] += end - spellStart;

		List<String> out = new ArrayList<>();
		out.add(String.format(Locale.ROOT, "%s / %s over %d days: %.1f changes per day, largest severity step %d (limit %d)",
			climate.name, season, days, changes / (double) days, maxStep, climate.maxSeverityStep));
		double total = days * (double) EPOCH_MS;
		for (int i = 0; i < ladder.types.length; i++) {
			var type = ladder.types[i];
			long[] s = spells.get(type);
			out.add(String.format(Locale.ROOT, "  %-16s weight %5.1f (%4.1f%%)  actual %4.1f%%  mean spell %s",
				type.name, ladder.weights[i], 100 * ladder.weights[i] / ladder.totalWeight,
				100 * time.get(type) / total, s[0] == 0 ? "-" : WeatherManager.formatDuration(s[1] / s[0])));
		}
		if (!climate.forbidden.isEmpty()) {
			var names = new ArrayList<String>();
			for (var p : climate.forbidden)
				names.add(p.key);
			out.add("  Never: " + String.join(", ", names));
		}
		return out;
	}

	public static String info(WeatherManager wm) {
		var s = wm.getState();
		if (!s.active)
			return wm.getOverrides().disabled ? "Weather is disabled (::117hd weather on)" :
				wm.isFailed() ? "Weather failed; see the log (::117hd weather on retries)" :
					"Weather isn't active here";
		String next = s.nextChangePhase == Long.MAX_VALUE ? "no change soon" :
			s.nextType + " " + arrival(s.nextChangePhase - s.phaseMillis, wm.getClock().getScale());
		var w = s.surfaceWind;
		return String.format(Locale.ROOT, "%s/%s: %s (%.2f) -> %s | exposure %.2f | wind %.0f deg, strength %.1f, speed %.1f | %s UTC%s",
			s.climate, s.season, s.type, s.intensity, next, s.exposure,
			w.windAngle * RAD_TO_DEG, w.windStrength, w.windSpeed,
			TIME.format(Instant.ofEpochMilli(s.clockMillis)),
			wm.getOverrides().any() || !wm.getClock().isRealTime(System.currentTimeMillis()) ? " [overrides]" : "");
	}

	/** Label/value rows for the debug overlay. */
	public static List<String[]> describe(WeatherManager wm) {
		List<String[]> rows = new ArrayList<>();
		var s = wm.getState();
		var clock = wm.getClock();
		long wall = System.currentTimeMillis();
		if (!s.active) {
			rows.add(new String[] { "Status", info(wm) });
			var w = s.surfaceWind;
			rows.add(new String[] { "Wind", String.format(Locale.ROOT, "%.0f deg str %.1f spd %.1f (environment)",
				w.windAngle * RAD_TO_DEG, w.windStrength, w.windSpeed) });
			return rows;
		}

		long offset = clock.getEffectiveOffset(wall);
		rows.add(new String[] { "Clock", TIME.format(Instant.ofEpochMilli(s.clockMillis)) + " UTC" +
			(clock.isRealTime(wall) ? "" : String.format(Locale.ROOT, " (%s, x%.1f)", WeatherManager.formatDuration(offset), clock.getScale())) });
		rows.add(new String[] { "Tile", s.worldPoint[0] + ", " + s.worldPoint[1] + ", " + s.worldPoint[2] });
		rows.add(new String[] { "Binding", s.bindingIndex < 0 ? "unbound" : "#" + s.bindingIndex + " " + s.bindingClimate });

		var blend = wm.getBlend();
		StringBuilder zones = new StringBuilder();
		for (int i = 0; i < blend.count && i < 3; i++) {
			var z = blend.zones[i];
			if (zones.length() > 0)
				zones.append(", ");
			zones.append(z.climate.name).append('/').append(z.season.name(), 0, 2);
			if (z.isForced())
				zones.append(" (forced ").append(z.forcedType).append(')');
			zones.append(String.format(Locale.ROOT, " %.2f", blend.weights[i]));
		}
		rows.add(new String[] { "Zones", zones.toString() });
		rows.add(new String[] { "Weather", String.format(Locale.ROOT, "%s (%.2f) after %s", s.type, s.intensity, s.previousType) });
		if (s.nextChangePhase != Long.MAX_VALUE)
			rows.add(new String[] { "Next", s.nextType + " " + arrival(s.nextChangePhase - s.phaseMillis, clock.getScale()) });
		if (s.transitionProgress > 0)
			rows.add(new String[] { "Transition", String.format(Locale.ROOT, "%.0f%% %s", s.transitionProgress * 100,
				s.transitionShape > 0 ? "worsening" : s.transitionShape < 0 ? "clearing" : "drifting") });
		rows.add(new String[] { "Exposure", String.format(Locale.ROOT, "%.2f (env %s, %s)", s.exposure,
			wm.isEnvironmentGateOpen() ? "open" : "closed", wm.isExposureLockstep() ? "lockstep" : "timed") });
		rows.add(new String[] { "Clouds", String.format(Locale.ROOT, "%.2f t%.1f b%.2f s%.2f hi%.2f dk%.2f",
			s.get(WeatherParam.CLOUD_COVER), s.get(WeatherParam.CLOUD_THICKNESS), s.get(WeatherParam.CLOUD_BILLOW),
			s.get(WeatherParam.CLOUD_SCALE), s.get(WeatherParam.HIGH_CLOUD_COVER), s.get(WeatherParam.CLOUD_DARKNESS)) });
		rows.add(new String[] { "Precip", String.format(Locale.ROOT, "r%.2f s%.2f a%.2f d%.2f st%.2f",
			s.get(WeatherParam.RAIN), s.get(WeatherParam.SNOW), s.get(WeatherParam.ASH), s.get(WeatherParam.DUST),
			s.get(WeatherParam.STORMINESS)) });
		rows.add(new String[] { "Haze / fog", String.format(Locale.ROOT, "%.2f / %.2f, overcast %.2f",
			s.get(WeatherParam.HAZE), s.get(WeatherParam.FOG), s.overcast) });
		var w = s.surfaceWind;
		rows.add(new String[] { "Wind", String.format(Locale.ROOT, "%.0f deg str %.1f spd %.1f gust %.2f",
			w.windAngle * RAD_TO_DEG, w.windStrength, w.windSpeed, w.gust) });
		rows.add(new String[] { "Wind base", String.format(Locale.ROOT, "%.0f deg str %.1f spd %.1f clim %.2f",
			w.baseAngle * RAD_TO_DEG, w.baseStrength, w.baseSpeed, w.climateShare) });
		var u = s.upperWind;
		rows.add(new String[] { "Upper wind", String.format(Locale.ROOT, "%.0f deg %.1f u/s dev %+.0f deg",
			u.angle * RAD_TO_DEG, u.speed, u.deviation * RAD_TO_DEG) });

		StringBuilder edges = new StringBuilder();
		var strip = s.strip;
		for (int i = WeatherStrip.CENTER - 1; i <= WeatherStrip.CENTER + 1; i++) {
			if (edges.length() > 0)
				edges.append("  ");
			edges.append(String.format(Locale.ROOT, "%+.0f~%.0f",
				strip.edgeDistance[i] / 128, strip.edgeWidth[i] / 128));
		}
		rows.add(new String[] { "Edges (tiles)", edges.toString() });

		StringBuilder masked = new StringBuilder();
		for (var p : WeatherParam.VALUES) {
			if (p.forbiddable && wm.getForbidMask(p.offset()) < 1) {
				if (masked.length() > 0)
					masked.append(", ");
				masked.append(p.key);
			}
		}
		if (masked.length() > 0)
			rows.add(new String[] { "Masked", masked.toString() });
		if (wm.getOverrides().any())
			rows.add(new String[] { "Overrides", describeOverrides(wm.getOverrides()) });
		return rows;
	}

	/** Real time until a phase delta elapses for a stationary observer, e.g. "in 12m" or "arriving now". */
	private static String arrival(long phaseDelta, double clockScale) {
		if (phaseDelta <= 0)
			return "arriving now";
		if (clockScale <= 0)
			return "paused";
		return "in " + WeatherManager.formatDuration((long) (phaseDelta / clockScale));
	}

	/** Print every strip entry and edge, to compare the sky against the data. */
	public static List<String> describeStrip(WeatherManager wm) {
		List<String> out = new ArrayList<>();
		var s = wm.getState();
		var strip = s.strip;
		if (!s.active || !strip.valid) {
			out.add("The weather controller isn't active here");
			return out;
		}
		out.add(String.format(Locale.ROOT, "Strip at %d, %d (drift axis %.0f deg; u > 0 is upwind, in tiles):",
			s.worldPoint[0], s.worldPoint[1], Math.toDegrees(Math.atan2(strip.axisZ, strip.axisX))));
		for (int i = 0; i < WeatherStrip.SIZE; i++) {
			float[] p = strip.params[i];
			out.add(String.format(Locale.ROOT, "%s#%d %s (%.2f): cover %.2f t%.1f billow %.2f high %.2f rain %.2f snow %.2f",
				i == WeatherStrip.CENTER ? "*" : " ", i - WeatherStrip.CENTER, strip.types[i], strip.intensities[i],
				p[WeatherParam.CLOUD_COVER.offset()], p[WeatherParam.CLOUD_THICKNESS.offset()],
				p[WeatherParam.CLOUD_BILLOW.offset()], p[WeatherParam.HIGH_CLOUD_COVER.offset()],
				p[WeatherParam.RAIN.offset()], p[WeatherParam.SNOW.offset()]));
			if (i < WeatherStrip.SIZE - 1) {
				out.add(String.format(Locale.ROOT, "   edge %+.0f, width %.0f, %s",
					strip.edgeDistance[i] / 128, strip.edgeWidth[i] / 128,
					strip.edgeShape[i] > 0 ? "worsening" : strip.edgeShape[i] < 0 ? "clearing" : "drifting"));
			}
		}
		return out;
	}

	private static String describeOverrides(WeatherOverrides o) {
		List<String> parts = new ArrayList<>();
		if (o.forcedTypeName != null)
			parts.add("type " + (o.heldTypeName != null ? o.heldTypeName + "->" : "") + o.forcedTypeName);
		if (o.forcedClimate != null)
			parts.add("climate " + o.forcedClimate);
		if (o.forcedSeason != null)
			parts.add("season " + o.forcedSeason);
		if (o.windOverride)
			parts.add("wind");
		if (o.disabled)
			parts.add("off");
		return String.join(", ", parts);
	}

	@Nullable
	public static Long parseDuration(String text) {
		Matcher m = DURATION.matcher(text.trim().toLowerCase(Locale.ROOT));
		if (!m.matches())
			return null;
		double value = Double.parseDouble(m.group(1));
		if (!Double.isFinite(value))
			return null;
		double unit;
		switch (m.group(2)) {
			case "s":
				unit = 1_000;
				break;
			case "h":
				unit = 3_600_000;
				break;
			case "d":
				unit = 86_400_000;
				break;
			default:
				unit = 60_000;
				break;
		}
		double ms = value * unit;
		if (Math.abs(ms) > 3650 * 86_400_000.0)
			return null;
		return Math.round(ms);
	}

	@Nullable
	private static Double parseFinite(String text) {
		try {
			double value = Double.parseDouble(text);
			return Double.isFinite(value) ? value : null;
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	@Nullable
	private static SeasonalTheme parseSeason(String text) {
		switch (text.toUpperCase(Locale.ROOT)) {
			case "SUMMER":
				return SeasonalTheme.SUMMER;
			case "AUTUMN":
			case "FALL":
				return SeasonalTheme.AUTUMN;
			case "WINTER":
				return SeasonalTheme.WINTER;
			default:
				return null;
		}
	}

	private static String typeList(@Nullable WeatherData data) {
		return data == null ? "" : ". Types: " + String.join(", ", data.types.keySet());
	}

	private static String climateList(@Nullable WeatherData data) {
		return data == null ? "" : ". Climates: " + String.join(", ", data.climates.keySet());
	}
}
