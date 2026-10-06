package dev.galactica.client.discovery;

import dev.galactica.client.sky.SkyManager;
import dev.galactica.discovery.Discovery;
import dev.galactica.discovery.DiscoveryPayloads;
import dev.galactica.discovery.ScanTarget;
import dev.galactica.science.Units;
import dev.galactica.science.spectra.PlanetSpectrum;
import dev.galactica.science.spectra.Spectrum;
import dev.galactica.science.spectra.StellarSpectrum;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import dev.galactica.system.SystemGenerator;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The result of a scan: the spectrum (rebuilt on the client from the target's address with the
 * server's radial velocity) plotted 350–2500 nm with its features labelled, and a panel with the
 * derived quantities, the discovery record and, for a star scanned from outside its system, the
 * planets the transit/RV survey revealed.
 */
public class ScanResultScreen extends Screen {
    private static final int TEXT = 0xFFD8E4FF, DIM = 0xFF8C9AB8, ACCENT = 0xFF7FE0FF, GOOD = 0xFF8CFF9E,
            GOLD = 0xFFFFD54F, BAD = 0xFFFF7A6E, PANEL = 0xC0000814;
    private static final int MARGIN = 8, ROW = 10;

    private record Row(Component text, int colour) {}

    private final DiscoveryPayloads.ScanResult result;
    private final ScanTarget.Resolved target;
    private final Spectrum spectrum;
    private final List<Row> rows = new ArrayList<>();
    private final String heading;
    private int plotLeft, plotTop, plotW, plotH, panelLeft, panelW;
    private int scroll;

    public ScanResultScreen(DiscoveryPayloads.ScanResult result) {
        super(Component.translatable("screen.galactica.scan_result"));
        this.result = result;
        this.target = result.target().resolve(SkyManager.get().galaxy());
        if (target == null) {
            spectrum = null;
            heading = "?";
            return;
        }
        if (target.isStar()) {
            Star s = target.star();
            spectrum = StellarSpectrum.of(s, result.rvKms());
            heading = title(Discovery.displayName(s), s.name());
            starRows(s);
        } else {
            Body b = target.body();
            PlanetSpectrum.Result pr = PlanetSpectrum.of(target.star(), b, PlanetSpectrum.illuminationDistance(target.planet()), result.rvKms());
            spectrum = pr.spectrum();
            heading = title(Discovery.displayName(b), b.name());
            bodyRows(b, pr);
        }
        discoveryRows();
        if (target.isStar()) surveyRows(target.star());
    }

    private static String title(String display, String designation) {
        return display.equals(designation) ? designation : display + " (" + designation + ")";
    }

    // ---------------------------------------------------------------- rows

    private void add(String key, int colour, Object... args) {
        rows.add(new Row(Component.translatable(key, args), colour));
    }

    private void gap() {
        rows.add(new Row(Component.empty(), TEXT));
    }

    private void starRows(Star s) {
        add("galactica.spectrum.type", ACCENT, s.spectralType());
        if (s.temperature() > 0) add("galactica.spectrum.teff", TEXT, fmt("%.0f", s.temperature()));
        add("galactica.spectrum.lum", TEXT, sig(s.luminosity()));
        add("galactica.spectrum.mass", TEXT, sig(s.mass()));
        add("galactica.spectrum.radius", TEXT, sig(s.radius()));
        add("galactica.spectrum.age", TEXT, sig(s.ageGyr()));
        add("galactica.spectrum.feh", TEXT, fmt("%+.2f", s.metallicity()));
        rvRows();
        if (spectrum != null && !spectrum.dark()) add("galactica.spectrum.peak", DIM, fmt("%.0f", spectrum.peakNm()));
        if (spectrum != null && spectrum.dark()) add("galactica.spectrum.dark", BAD);
    }

    private void bodyRows(Body b, PlanetSpectrum.Result pr) {
        add("galactica.spectrum.body_type", ACCENT, b.type().label);
        add("galactica.spectrum.teq", TEXT, fmt("%.0f", b.equilibriumTemp()));
        if (b.type().solidSurface) add("galactica.spectrum.surface", TEXT, fmt("%.0f", b.surfaceTemp()), sig(b.surfacePressureBar()));
        else add("galactica.spectrum.giant", TEXT, fmt("%.0f", b.surfaceTemp()));
        add("galactica.spectrum.mass_radius", TEXT, sig(b.massEarth()), sig(b.radiusEarth()));
        add("galactica.spectrum.gravity", TEXT, fmt("%.2f", b.surfaceGravityG()));
        add("galactica.spectrum.orbit", TEXT, sig(PlanetSpectrum.au(PlanetSpectrum.illuminationDistance(target.planet()))));
        if (pr.gases().isEmpty()) add("galactica.spectrum.gases.none", DIM);
        else add("galactica.spectrum.gases", TEXT, String.join(", ", pr.gases()));
        if (pr.biosignature()) add("galactica.spectrum.bio.yes", GOOD);
        else add("galactica.spectrum.bio.no", DIM);
        rvRows();
    }

    private void rvRows() {
        add("galactica.spectrum.distance", TEXT, distance(result.distancePc()));
        String dir = result.rvKms() >= 0 ? "galactica.spectrum.rv.red" : "galactica.spectrum.rv.blue";
        rows.add(new Row(Component.translatable("galactica.spectrum.rv", fmt("%+.2f", result.rvKms()))
                .append(" ").append(Component.translatable(dir)), TEXT));
    }

    private void discoveryRows() {
        gap();
        if (result.first()) add("galactica.discovery.first", GOLD, result.awarded());
        else {
            add("galactica.discovery.known", TEXT, result.discoverer(), result.discoveryTime() / 24000L);
            if (result.awarded() > 0) add("galactica.discovery.awarded", GOLD, result.awarded());
        }
        if (!result.customName().isEmpty()) add("galactica.discovery.custom", ACCENT, result.customName());
        else if (result.first()) add("galactica.discovery.rename_hint", DIM);
        add("galactica.discovery.balance", TEXT, result.balance());
    }

    private void surveyRows(Star s) {
        gap();
        if (result.insideSystem()) {
            add("galactica.survey.inside", DIM);
            return;
        }
        if (result.revealed().isEmpty()) {
            add("galactica.survey.none", DIM);
            return;
        }
        add("galactica.survey.header", ACCENT, result.revealed().size());
        List<Body> planets = SystemGenerator.generate(s);
        for (DiscoveryPayloads.Revealed r : result.revealed()) {
            if (r.planetIndex() < 0 || r.planetIndex() >= planets.size()) continue;
            Body p = planets.get(r.planetIndex());
            Component how = Component.empty();
            if (r.transit()) how = how.copy().append(Component.translatable("galactica.survey.transit", fmt("%.0f", r.depthPpm())));
            if (r.transit() && r.rv()) how = how.copy().append(", ");
            if (r.rv()) how = how.copy().append(Component.translatable("galactica.survey.rv", sig(r.kMs())));
            Component line = Component.translatable("galactica.survey.row", Discovery.displayName(p), sig(r.periodDays()), how);
            if (r.first()) line = line.copy().append(" ").append(Component.translatable("galactica.survey.new"));
            rows.add(new Row(line, r.first() ? GOLD : TEXT));
        }
    }

    // ---------------------------------------------------------------- layout and drawing

    @Override
    protected void init() {
        panelW = Math.min(230, Math.max(160, width / 3));
        panelLeft = width - panelW - MARGIN;
        plotLeft = MARGIN + 4;
        plotTop = MARGIN + 14 + SpectrumPlot.LABEL_ROWS * SpectrumPlot.ROW_H + 2;
        plotW = Math.max(120, panelLeft - plotLeft - MARGIN - 4);
        plotH = Math.max(60, height - plotTop - 30 - MARGIN);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xF0000208);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.text(font, heading, MARGIN, MARGIN, ACCENT, false);
        if (target == null || spectrum == null) {
            g.centeredText(font, Component.translatable("galactica.scan.refused.unknown"), width / 2, height / 2, BAD);
            return;
        }
        SpectrumPlot.draw(g, font, spectrum, plotLeft, plotTop, plotW, plotH);
        g.centeredText(font, Component.translatable("galactica.spectrum.axis"), plotLeft + plotW / 2, plotTop + plotH + 18, DIM);
        if (mouseX >= plotLeft && mouseX < plotLeft + plotW && mouseY >= plotTop && mouseY < plotTop + plotH) {
            double nm = SpectrumPlot.nm(mouseX, plotLeft, plotW);
            String probe = fmt("λ %.0f nm  F %.3f", nm, spectrum.fluxAt(nm));
            g.fill(mouseX, plotTop, mouseX + 1, plotTop + plotH, 0x44FFFFFF);
            g.text(font, probe, plotLeft + plotW - font.width(probe) - 3, plotTop + 3, TEXT, true);
        }

        int y0 = MARGIN, h = height - 2 * MARGIN;
        g.fill(panelLeft, y0, panelLeft + panelW, y0 + h, PANEL);
        g.fill(panelLeft, y0, panelLeft + 2, y0 + h, ACCENT);
        g.enableScissor(panelLeft, y0 + 4, panelLeft + panelW, y0 + h - 4);
        int y = y0 + 6 - scroll;
        for (Row r : rows) {
            g.text(font, r.text(), panelLeft + 8, y, r.colour(), false);
            y += ROW;
        }
        g.disableScissor();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, rows.size() * ROW - (height - 2 * MARGIN - 12));
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.round(scrollY * ROW * 2)));
        return true;
    }

    // ---------------------------------------------------------------- formatting

    static String fmt(String f, Object... a) {
        return String.format(Locale.ROOT, f, a);
    }

    /** Three significant figures, switching to scientific notation outside 10⁻³…10⁶. */
    static String sig(double v) {
        double a = Math.abs(v);
        if (a == 0) return "0";
        if (a >= 1e6 || a < 1e-3) return fmt("%.2e", v);
        int digits = Math.max(0, 2 - (int) Math.floor(Math.log10(a)));
        return fmt("%." + digits + "f", v);
    }

    static String distance(double pc) {
        if (pc >= 0.05) return fmt("%s pc (%s ly)", sig(pc), sig(pc * Units.PARSEC / Units.LIGHT_YEAR));
        double au = pc * Units.PARSEC / Units.AU;
        if (au >= 0.01) return fmt("%s AU", sig(au));
        return fmt("%s km", sig(au * Units.AU / 1000.0));
    }
}
