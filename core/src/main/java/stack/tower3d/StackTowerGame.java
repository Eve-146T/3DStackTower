package stack.tower3d;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

/**
 * 3D Stack Tower.
 *
 * A block slides back and forth above the tower; tap to drop it. Whatever
 * hangs over the edge is sliced off and falls away. Landing exactly on top
 * (within one frame of travel) is a "frame perfect": the block keeps its
 * full size and the placement scores double.
 */
public class StackTowerGame extends ApplicationAdapter {

    private static final float BLOCK_HEIGHT = 0.6f;
    private static final float BASE_SIZE = 4.2f;
    private static final float TRAVEL = 4.5f;
    private static final float BASE_SPEED = 4.0f;
    private static final float SPEED_PER_LEVEL = 0.07f;
    private static final float MAX_SPEED = 8.5f;
    private static final float GRAVITY = 28f;
    private static final int VISIBLE_BLOCKS = 36;
    private static final int STAR_COUNT = 140;
    private static final int SCORE_NORMAL = 1;
    private static final int SCORE_PERFECT = 2; // double on frame perfects

    // Text is only crisp when the glyph atlas is rendered at the exact pixel
    // size it is shown at (scale 1.0, integer positions) — any rescale of a
    // fixed-size atlas blurs it. So each text role gets its own font whose
    // pixel size is derived from the screen width.
    private static final float FONT_BIG_FRAC = 0.115f;   // score
    private static final float FONT_MID_FRAC = 0.078f;   // titles
    private static final float FONT_SMALL_FRAC = 0.050f; // labels, buttons
    private static final String FONT_CHARS = " +.0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    // Extra top inset for the score / best so they sit clear of the camera
    // hole-punch.
    private static final float TOP_SAFE_FRAC = 0.0671f;

    // Camera zoom multipliers cycled by the Zoom button; 1 = default framing.
    // After zooming in (1.2) it jumps out to 0.5 and climbs back toward 1.
    private static final float[] ZOOM_LEVELS = {1f, 1.1f, 1.2f, 0.5f, 0.75f, 0.9f};
    // Pre-rendered labels (the font has no lowercase, so values stay numeric).
    private static final String[] ZOOM_LABELS = {"1", "1.1", "1.2", "0.5", "0.75", "0.9"};

    private enum State { READY, PLAYING, GAME_OVER }

    private static class Block {
        Model model;
        ModelInstance instance;
        final Vector3 center = new Vector3();
        float sizeX, sizeZ;
    }

    private static class Rubble {
        Model model;
        ModelInstance instance;
        final Vector3 center = new Vector3();
        final Vector3 velocity = new Vector3();
        final Vector3 spinAxis = new Vector3();
        float angle;
        float spinSpeed;
    }

    private static class Popup {
        String text;
        float age;
        Color color;
    }

    private ModelBatch modelBatch;
    private Environment environment;
    private PerspectiveCamera camera;
    private ModelBuilder modelBuilder;

    private SpriteBatch uiBatch;
    private BitmapFont fontBig, fontMid, fontSmall;
    private int fontGenWidth; // screen width the fonts were generated for
    private final GlyphLayout layout = new GlyphLayout();
    private Texture whitePixel;
    private float[] stars; // packed: x, y, size, brightness, twinklePhase
    private Preferences prefs;

    private Sound sndPlace, sndPerfect, sndGameOver, sndClick;
    private boolean soundOn, hapticsOn;
    private int perfectStreak; // consecutive perfects; raises the chime pitch
    private int zoomIndex; // index into ZOOM_LEVELS / ZOOM_LABELS
    private final Rectangle soundBtn = new Rectangle();
    private final Rectangle hapticBtn = new Rectangle();
    private final Rectangle zoomBtn = new Rectangle();

    private final Array<Block> tower = new Array<>();
    private final Array<Rubble> rubblePieces = new Array<>();
    private final Array<Popup> popups = new Array<>();

    private State state = State.READY;
    private Block movingBlock;
    private int level;           // index of the block currently in flight
    private int axis;            // 0 = x, 2 = z
    private float direction = 1f;
    private float speed;
    private int score;
    private int best;
    private float flashAlpha;
    private float gameOverTimer;
    private float clock;

    private float cameraY;       // smoothed look-at height
    private float cameraDist;    // smoothed camera distance
    private final Vector3 lookAt = new Vector3();

    @Override
    public void create() {
        modelBatch = new ModelBatch();
        modelBuilder = new ModelBuilder();

        environment = new Environment();
        environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.45f, 0.45f, 0.5f, 1f));
        environment.add(new DirectionalLight().set(0.85f, 0.85f, 0.8f, -0.6f, -0.9f, -0.3f));
        environment.add(new DirectionalLight().set(0.25f, 0.25f, 0.3f, 0.5f, -0.3f, 0.7f));

        camera = new PerspectiveCamera(50, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.near = 0.5f;
        camera.far = 300f;

        uiBatch = new SpriteBatch();
        buildFonts(Gdx.graphics.getWidth());

        Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(Color.WHITE);
        pixmap.fill();
        whitePixel = new Texture(pixmap);
        pixmap.dispose();

        generateStars();

        prefs = Gdx.app.getPreferences("stacktower3d");
        best = prefs.getInteger("best", 0);
        soundOn = prefs.getBoolean("sound", true);
        hapticsOn = prefs.getBoolean("haptics", true);
        zoomIndex = MathUtils.clamp(prefs.getInteger("zoom", 0), 0, ZOOM_LEVELS.length - 1);

        sndPlace = loadSound("sfx/place.wav");
        sndPerfect = loadSound("sfx/perfect.wav");
        sndGameOver = loadSound("sfx/gameover.wav");
        sndClick = loadSound("sfx/click.wav");

        resetGame();
    }

    /**
     * (Re)generates the three UI fonts at the exact pixel sizes they are
     * displayed at, so every glyph maps 1:1 onto screen pixels. Falls back to
     * the scaled built-in font if no system TTF is available (e.g. on a
     * desktop test run).
     */
    private void buildFonts(int width) {
        if (width <= 0 || width == fontGenWidth) return;
        fontGenWidth = width;
        if (fontBig != null) fontBig.dispose();
        if (fontMid != null) fontMid.dispose();
        if (fontSmall != null) fontSmall.dispose();

        FreeTypeFontGenerator generator = newSystemFontGenerator();
        if (generator != null) {
            // The big font doubles as the popup font; popups animate their
            // scale downward from 1.0, so it gets mipmaps for smooth minify.
            fontBig = generateFont(generator, Math.round(width * FONT_BIG_FRAC), true);
            fontMid = generateFont(generator, Math.round(width * FONT_MID_FRAC), false);
            fontSmall = generateFont(generator, Math.round(width * FONT_SMALL_FRAC), false);
            generator.dispose();
        } else {
            fontBig = builtinFont(width * FONT_BIG_FRAC);
            fontMid = builtinFont(width * FONT_MID_FRAC);
            fontSmall = builtinFont(width * FONT_SMALL_FRAC);
        }
    }

    /** Opens the first usable system TTF; null if none can be loaded. */
    private FreeTypeFontGenerator newSystemFontGenerator() {
        Array<FileHandle> candidates = new Array<>();
        String[] preferred = {
                "/system/fonts/Roboto-Regular.ttf",
                "/system/fonts/RobotoStatic-Regular.ttf",
                "/system/fonts/DroidSans.ttf",
                "/system/fonts/NotoSans-Regular.ttf",
        };
        for (String path : preferred) {
            candidates.add(Gdx.files.absolute(path));
        }
        try {
            // Last resort: any TTF the device ships (OEMs rename things).
            FileHandle[] all = Gdx.files.absolute("/system/fonts").list(".ttf");
            for (FileHandle handle : all) candidates.add(handle);
        } catch (Throwable ignored) {
        }
        int tried = 0;
        for (FileHandle handle : candidates) {
            if (tried >= 10) break;
            try {
                if (!handle.exists()) continue;
                tried++;
                return new FreeTypeFontGenerator(handle);
            } catch (Throwable ignored) {
                // Unreadable or not a valid font; try the next one.
            }
        }
        return null;
    }

    private BitmapFont generateFont(FreeTypeFontGenerator generator, int sizePx, boolean mipMaps) {
        FreeTypeFontParameter parameter = new FreeTypeFontParameter();
        parameter.size = sizePx;
        parameter.characters = FONT_CHARS;
        parameter.genMipMaps = mipMaps;
        parameter.minFilter = mipMaps
                ? Texture.TextureFilter.MipMapLinearLinear
                : Texture.TextureFilter.Linear;
        parameter.magFilter = Texture.TextureFilter.Linear;
        BitmapFont font = generator.generateFont(parameter);
        font.setUseIntegerPositions(true);
        return font;
    }

    private BitmapFont builtinFont(float emPx) {
        BitmapFont font = new BitmapFont();
        font.getRegion().getTexture().setFilter(
                Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        font.getData().setScale(emPx / 15f); // the built-in font is 15 px
        font.setUseIntegerPositions(false);  // rounding distorts fractional scales
        return font;
    }

    private Sound loadSound(String path) {
        try {
            FileHandle handle = Gdx.files.internal(path);
            if (!handle.exists()) return null;
            return Gdx.audio.newSound(handle);
        } catch (Throwable t) {
            return null;
        }
    }

    private void playSound(Sound sound, float volume, float pitch) {
        if (soundOn && sound != null) sound.play(volume, pitch, 0f);
    }

    /** Amplitude-shaped vibration; falls back to a plain buzz on old devices. */
    private void buzz(int milliseconds, int amplitude) {
        if (hapticsOn) Gdx.input.vibrate(milliseconds, amplitude, true);
    }

    private void toggleSound() {
        soundOn = !soundOn;
        prefs.putBoolean("sound", soundOn);
        prefs.flush();
        playSound(sndClick, 0.6f, 1f); // audible only when switched on
    }

    private void toggleHaptics() {
        hapticsOn = !hapticsOn;
        prefs.putBoolean("haptics", hapticsOn);
        prefs.flush();
        playSound(sndClick, 0.6f, 1f);
        buzz(30, 140); // felt only when switched on
    }

    private void cycleZoom() {
        zoomIndex = (zoomIndex + 1) % ZOOM_LEVELS.length;
        prefs.putInteger("zoom", zoomIndex);
        prefs.flush();
        playSound(sndClick, 0.6f, 1f);
    }

    private void generateStars() {
        com.badlogic.gdx.math.RandomXS128 rng = new com.badlogic.gdx.math.RandomXS128(0xC0FFEEL);
        stars = new float[STAR_COUNT * 5];
        for (int i = 0; i < STAR_COUNT; i++) {
            int o = i * 5;
            stars[o]     = rng.nextFloat();              // x as fraction of width
            stars[o + 1] = rng.nextFloat();              // y as fraction of height
            stars[o + 2] = 0.6f + rng.nextFloat() * 2.0f; // size in dp
            stars[o + 3] = 0.3f + rng.nextFloat() * 0.7f; // base brightness
            stars[o + 4] = rng.nextFloat() * 6.2832f;     // twinkle phase
        }
    }

    private void resetGame() {
        for (Block block : tower) block.model.dispose();
        tower.clear();
        if (movingBlock != null) {
            movingBlock.model.dispose();
            movingBlock = null;
        }
        for (Rubble rubble : rubblePieces) rubble.model.dispose();
        rubblePieces.clear();
        popups.clear();

        Block base = makeBlock(BASE_SIZE, BASE_SIZE, colorForLevel(0));
        base.center.set(0f, 0f, 0f);
        applyTransform(base);
        tower.add(base);

        level = 1;
        axis = 0;
        score = 0;
        perfectStreak = 0;
        flashAlpha = 0f;
        speed = BASE_SPEED;
        cameraY = 0f;
        cameraDist = 13f;
        state = State.READY;
        spawnMovingBlock();
    }

    private Block makeBlock(float sizeX, float sizeZ, Color color) {
        Block block = new Block();
        block.sizeX = sizeX;
        block.sizeZ = sizeZ;
        Material material = new Material(ColorAttribute.createDiffuse(color));
        block.model = modelBuilder.createBox(sizeX, BLOCK_HEIGHT, sizeZ, material,
                Usage.Position | Usage.Normal);
        block.instance = new ModelInstance(block.model);
        return block;
    }

    private void applyTransform(Block block) {
        block.instance.transform.setToTranslation(block.center);
    }

    private void spawnMovingBlock() {
        Block top = tower.peek();
        movingBlock = makeBlock(top.sizeX, top.sizeZ, colorForLevel(level));
        movingBlock.center.set(top.center.x, level * BLOCK_HEIGHT, top.center.z);
        axis = (level % 2 == 0) ? 0 : 2;
        direction = 1f;
        setAxisPos(movingBlock.center, axisPos(top.center) - TRAVEL);
        speed = Math.min(BASE_SPEED + level * SPEED_PER_LEVEL, MAX_SPEED);
        applyTransform(movingBlock);
    }

    private float axisPos(Vector3 v) {
        return axis == 0 ? v.x : v.z;
    }

    private void setAxisPos(Vector3 v, float value) {
        if (axis == 0) v.x = value; else v.z = value;
    }

    private Color colorForLevel(int lvl) {
        float hue = (lvl * 11f) % 360f;
        return hsvToColor(hue, 0.55f, 0.92f);
    }

    private static Color hsvToColor(float h, float s, float v) {
        float c = v * s;
        float x = c * (1f - Math.abs((h / 60f) % 2f - 1f));
        float m = v - c;
        float r, g, b;
        if (h < 60)       { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else              { r = c; g = 0; b = x; }
        return new Color(r + m, g + m, b + m, 1f);
    }

    @Override
    public void render() {
        float delta = Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f);
        update(delta);

        // Deep night-sky gradient base so the stars read clearly.
        Color bg = hsvToColor((225f + level * 1.5f) % 360f, 0.6f, 0.09f);
        Gdx.gl.glClearColor(bg.r, bg.g, bg.b, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

        drawStars();

        modelBatch.begin(camera);
        int first = Math.max(0, tower.size - VISIBLE_BLOCKS);
        for (int i = first; i < tower.size; i++) {
            modelBatch.render(tower.get(i).instance, environment);
        }
        if (movingBlock != null && state != State.GAME_OVER) {
            modelBatch.render(movingBlock.instance, environment);
        }
        for (Rubble rubble : rubblePieces) {
            modelBatch.render(rubble.instance, environment);
        }
        modelBatch.end();

        drawUi();
    }

    private void update(float delta) {
        clock += delta;
        if (Gdx.input.justTouched()) {
            handleTap();
        }

        if (state == State.PLAYING && movingBlock != null) {
            Block top = tower.peek();
            float pos = axisPos(movingBlock.center) + direction * speed * delta;
            float lower = axisPos(top.center) - TRAVEL;
            float upper = axisPos(top.center) + TRAVEL;
            if (pos > upper) { pos = upper - (pos - upper); direction = -1f; }
            else if (pos < lower) { pos = lower + (lower - pos); direction = 1f; }
            setAxisPos(movingBlock.center, pos);
            applyTransform(movingBlock);
        }

        updateRubble(delta);
        updateCamera(delta);

        if (state == State.GAME_OVER) gameOverTimer += delta;
        flashAlpha = Math.max(0f, flashAlpha - delta * 2.5f);

        for (int i = popups.size - 1; i >= 0; i--) {
            popups.get(i).age += delta;
            if (popups.get(i).age > 1.2f) popups.removeIndex(i);
        }
    }

    private void handleTap() {
        switch (state) {
            case READY:
                float touchX = Gdx.input.getX();
                float touchY = Gdx.graphics.getHeight() - Gdx.input.getY();
                layoutButtons();
                if (soundBtn.contains(touchX, touchY)) {
                    toggleSound();
                } else if (hapticBtn.contains(touchX, touchY)) {
                    toggleHaptics();
                } else if (zoomBtn.contains(touchX, touchY)) {
                    cycleZoom();
                } else {
                    playSound(sndClick, 0.4f, 1f);
                    state = State.PLAYING;
                }
                break;
            case PLAYING:
                placeBlock();
                break;
            case GAME_OVER:
                if (gameOverTimer > 0.6f) {
                    playSound(sndClick, 0.4f, 1f);
                    resetGame();
                }
                break;
        }
    }

    private void placeBlock() {
        Block top = tower.peek();
        float blockSize = axis == 0 ? movingBlock.sizeX : movingBlock.sizeZ;
        float deltaPos = axisPos(movingBlock.center) - axisPos(top.center);
        float overlap = blockSize - Math.abs(deltaPos);

        if (overlap <= 0f) {
            // Complete miss: the block tumbles off and the run ends.
            dropAsRubble(movingBlock, Math.signum(deltaPos));
            movingBlock = null;
            endGame();
            return;
        }

        // Frame perfect: landed within one frame of travel of dead center.
        float perfectEps = speed * (1f / 60f);
        boolean perfect = Math.abs(deltaPos) <= perfectEps;

        if (perfect) {
            setAxisPos(movingBlock.center, axisPos(top.center));
            applyTransform(movingBlock);
            tower.add(movingBlock);
            score += SCORE_PERFECT;
            flashAlpha = 0.55f;
            addPopup("PERFECT  +" + SCORE_PERFECT, new Color(1f, 0.92f, 0.4f, 1f));
            perfectStreak++;
            float pitch = Math.min(1f + 0.05f * (perfectStreak - 1), 1.5f);
            playSound(sndPerfect, 0.8f, pitch);
            buzz(32, 150);
        } else {
            float newSize = overlap;
            float newCenter = axisPos(top.center) + deltaPos / 2f;
            float rubbleSize = Math.abs(deltaPos);
            float rubbleCenter = newCenter + Math.signum(deltaPos) * (newSize + rubbleSize) / 2f;

            Block placed = makeBlock(
                    axis == 0 ? newSize : movingBlock.sizeX,
                    axis == 2 ? newSize : movingBlock.sizeZ,
                    colorForLevel(level));
            placed.center.set(movingBlock.center);
            setAxisPos(placed.center, newCenter);
            applyTransform(placed);
            tower.add(placed);

            Rubble rubble = new Rubble();
            Material material = new Material(ColorAttribute.createDiffuse(colorForLevel(level)));
            rubble.model = modelBuilder.createBox(
                    axis == 0 ? rubbleSize : movingBlock.sizeX,
                    BLOCK_HEIGHT,
                    axis == 2 ? rubbleSize : movingBlock.sizeZ,
                    material, Usage.Position | Usage.Normal);
            rubble.instance = new ModelInstance(rubble.model);
            rubble.center.set(movingBlock.center);
            setAxisPos(rubble.center, rubbleCenter);
            rubble.velocity.set(0f, 1.5f, 0f);
            if (axis == 0) rubble.velocity.x = Math.signum(deltaPos) * 2.5f;
            else rubble.velocity.z = Math.signum(deltaPos) * 2.5f;
            rubble.spinAxis.set(axis == 0 ? 0f : 1f, 0f, axis == 0 ? 1f : 0f);
            rubble.spinSpeed = Math.signum(deltaPos) * 220f;
            rubblePieces.add(rubble);

            movingBlock.model.dispose();
            score += SCORE_NORMAL;
            perfectStreak = 0;
            playSound(sndPlace, 0.75f, MathUtils.random(0.96f, 1.05f));
            buzz(18, 80);
        }

        movingBlock = null;
        level++;
        spawnMovingBlock();
    }

    private void dropAsRubble(Block block, float side) {
        Rubble rubble = new Rubble();
        rubble.model = block.model; // ownership moves to the rubble piece
        rubble.instance = block.instance;
        rubble.center.set(block.center);
        rubble.velocity.set(0f, 0.5f, 0f);
        if (axis == 0) rubble.velocity.x = side * 2f;
        else rubble.velocity.z = side * 2f;
        rubble.spinAxis.set(axis == 0 ? 0f : 1f, 0f, axis == 0 ? 1f : 0f);
        rubble.spinSpeed = side * 180f;
        rubblePieces.add(rubble);
    }

    private void endGame() {
        state = State.GAME_OVER;
        gameOverTimer = 0f;
        playSound(sndGameOver, 0.85f, 1f);
        buzz(110, 255);
        if (score > best) {
            best = score;
            prefs.putInteger("best", best);
            prefs.flush();
        }
    }

    private void updateRubble(float delta) {
        for (int i = rubblePieces.size - 1; i >= 0; i--) {
            Rubble rubble = rubblePieces.get(i);
            rubble.velocity.y -= GRAVITY * delta;
            rubble.center.mulAdd(rubble.velocity, delta);
            rubble.angle += rubble.spinSpeed * delta;
            rubble.instance.transform.setToTranslation(rubble.center)
                    .rotate(rubble.spinAxis, rubble.angle);
            if (rubble.center.y < cameraY - 25f) {
                rubble.model.dispose();
                rubblePieces.removeIndex(i);
            }
        }
    }

    private void updateCamera(float delta) {
        float topY = (tower.size - 1) * BLOCK_HEIGHT;
        float targetY, targetDist;
        if (state == State.GAME_OVER) {
            // Pull back to show the whole tower.
            targetY = topY * 0.5f;
            targetDist = Math.max(13f, topY * 0.85f + 7f);
        } else {
            targetY = topY;
            // Higher zoom pulls the camera in; lower zoom backs it off.
            targetDist = 13f / ZOOM_LEVELS[zoomIndex];
        }
        cameraY = MathUtils.lerp(cameraY, targetY, Math.min(1f, delta * 4f));
        cameraDist = MathUtils.lerp(cameraDist, targetDist, Math.min(1f, delta * 3f));

        lookAt.set(0f, cameraY + 0.5f, 0f);
        camera.position.set(cameraDist * 0.62f, cameraY + cameraDist * 0.78f, cameraDist * 0.62f);
        camera.up.set(0f, 1f, 0f);
        camera.lookAt(lookAt);
        camera.update();
    }

    private void addPopup(String text, Color color) {
        Popup popup = new Popup();
        popup.text = text;
        popup.age = 0f;
        popup.color = color;
        popups.add(popup);
    }

    private void drawStars() {
        if (stars == null) return;
        float w = Gdx.graphics.getWidth();
        float h = Gdx.graphics.getHeight();
        float dp = Math.max(1f, w / 240f);
        // Stars drift downward slightly as the camera climbs (parallax),
        // wrapping so the field never runs out.
        float scroll = cameraY * 6f;

        uiBatch.begin();
        for (int i = 0; i < STAR_COUNT; i++) {
            int o = i * 5;
            float sx = stars[o] * w;
            // Per-star parallax depth: dimmer/smaller stars scroll slower.
            float depth = 0.4f + stars[o + 3] * 0.6f;
            float sy = ((stars[o + 1] * h - scroll * depth) % h + h) % h;
            float size = stars[o + 2] * dp;
            float twinkle = 0.7f + 0.3f * MathUtils.sin(clock * 2.2f + stars[o + 4]);
            float b = stars[o + 3] * twinkle;
            uiBatch.setColor(b, b, b * 0.95f + 0.05f, 1f);
            uiBatch.draw(whitePixel, sx, sy, size, size);
        }
        uiBatch.setColor(Color.WHITE);
        uiBatch.end();
    }

    private void drawUi() {
        float w = Gdx.graphics.getWidth();
        float h = Gdx.graphics.getHeight();

        uiBatch.begin();

        if (flashAlpha > 0f) {
            uiBatch.setColor(1f, 1f, 1f, flashAlpha);
            uiBatch.draw(whitePixel, 0, 0, w, h);
            uiBatch.setColor(Color.WHITE);
        }

        fontBig.setColor(Color.WHITE);
        drawCentered(fontBig, String.valueOf(score), w / 2f, h - (0.083f + TOP_SAFE_FRAC) * w);

        fontSmall.setColor(0.8f, 0.85f, 0.95f, 1f);
        drawCentered(fontSmall, "BEST " + best, w / 2f, h - (0.2f + TOP_SAFE_FRAC) * w);

        for (Popup popup : popups) {
            float t = popup.age / 1.2f;
            fontBig.getData().setScale(0.62f + 0.18f * t);
            fontBig.setColor(popup.color.r, popup.color.g, popup.color.b, 1f - t);
            drawCentered(fontBig, popup.text, w / 2f, h * 0.62f + t * 0.1f * w);
        }
        fontBig.getData().setScale(1f);

        if (state == State.READY) {
            fontMid.setColor(Color.WHITE);
            drawCentered(fontMid, "STACK TOWER 3D", w / 2f, h * 0.46f);
            float pulse = 0.6f + 0.4f * MathUtils.sin(clock * 4f);
            fontSmall.setColor(1f, 1f, 1f, pulse);
            drawCentered(fontSmall, "TAP TO START", w / 2f, h * 0.38f);
            drawToggleButtons();
        } else if (state == State.GAME_OVER) {
            fontMid.setColor(1f, 0.45f, 0.45f, 1f);
            drawCentered(fontMid, "GAME OVER", w / 2f, h * 0.5f);
            fontSmall.setColor(Color.WHITE);
            drawCentered(fontSmall, "SCORE " + score + "   BEST " + best, w / 2f, h * 0.43f);
            if (gameOverTimer > 0.6f) {
                float pulse = 0.6f + 0.4f * MathUtils.sin(clock * 4f);
                fontSmall.setColor(1f, 1f, 1f, pulse);
                drawCentered(fontSmall, "TAP TO RESTART", w / 2f, h * 0.36f);
            }
        }

        fontBig.setColor(Color.WHITE);
        fontMid.setColor(Color.WHITE);
        fontSmall.setColor(Color.WHITE);
        uiBatch.end();
    }

    /** Places the sound/haptics toggles in the bottom-left corner. */
    private void layoutButtons() {
        float cap = fontSmall.getCapHeight();
        layout.setText(fontSmall, "HAPTICS OFF"); // widest label
        float btnW = layout.width + cap * 1.8f;
        float btnH = cap * 2.4f;
        float margin = cap * 0.9f;
        float gap = cap * 0.55f;
        hapticBtn.set(margin, margin, btnW, btnH);
        soundBtn.set(margin, margin + btnH + gap, btnW, btnH);
        zoomBtn.set(margin, margin + 2f * (btnH + gap), btnW, btnH);
    }

    private void drawToggleButtons() {
        layoutButtons();
        drawButton(soundBtn, "SOUND " + (soundOn ? "ON" : "OFF"), soundOn);
        drawButton(hapticBtn, "HAPTICS " + (hapticsOn ? "ON" : "OFF"), hapticsOn);
        drawButton(zoomBtn, "ZOOM " + ZOOM_LABELS[zoomIndex], true);
    }

    private void drawButton(Rectangle r, String label, boolean on) {
        float border = Math.max(1f, fontSmall.getCapHeight() * 0.07f);
        uiBatch.setColor(1f, 1f, 1f, on ? 0.35f : 0.16f);
        uiBatch.draw(whitePixel, r.x, r.y, r.width, r.height);
        uiBatch.setColor(0.05f, 0.06f, 0.12f, 0.85f);
        uiBatch.draw(whitePixel, r.x + border, r.y + border,
                r.width - 2f * border, r.height - 2f * border);
        uiBatch.setColor(Color.WHITE);
        fontSmall.setColor(1f, 1f, 1f, on ? 0.95f : 0.4f);
        float cap = fontSmall.getCapHeight();
        drawCentered(fontSmall, label, r.x + r.width / 2f, r.y + (r.height + cap) / 2f);
    }

    private void drawCentered(BitmapFont font, String text, float x, float y) {
        layout.setText(font, text);
        font.draw(uiBatch, layout, Math.round(x - layout.width / 2f), Math.round(y));
    }

    @Override
    public void resize(int width, int height) {
        camera.viewportWidth = width;
        camera.viewportHeight = height;
        camera.update();
        uiBatch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
        buildFonts(width);
    }

    @Override
    public void dispose() {
        for (Block block : tower) block.model.dispose();
        if (movingBlock != null) movingBlock.model.dispose();
        for (Rubble rubble : rubblePieces) rubble.model.dispose();
        modelBatch.dispose();
        uiBatch.dispose();
        if (fontBig != null) fontBig.dispose();
        if (fontMid != null) fontMid.dispose();
        if (fontSmall != null) fontSmall.dispose();
        if (sndPlace != null) sndPlace.dispose();
        if (sndPerfect != null) sndPerfect.dispose();
        if (sndGameOver != null) sndGameOver.dispose();
        if (sndClick != null) sndClick.dispose();
        whitePixel.dispose();
    }
}
