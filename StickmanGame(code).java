import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.sound.sampled.*;

public class StickmanGame extends JWindow implements ActionListener {

    // ====== CLONE MANAGEMENT ======
    private static final List<StickmanGame> CLONES = new ArrayList<>();
    private static final int MAX_CLONES = 5;

    // ====== SCREEN / WORLD ======
    private final int screenWidth;
    private final int screenHeight;
    private final int baseGroundY;
    private final int roofY = 40;
    private final int leftWallX = 25;
    private final int rightWallX;

    // ====== STICKMAN STATE ======
    // 0=Idle, 1=WalkL, 2=WalkR, 3=Sit, 4=Fall, 5=Drag, 6=ClimbL, 7=ClimbR, 8=Ceiling, 9=Dance, 10=Yay, 11=Sleep, 12=Build
    private int currentState = 4;
    private int previousState = 4;
    private int buildMode = 0;
    private int buildFix = 0;
    // Add this at the top of your class (NOT inside a method)
private int climbFrame = 0;
private long lastClimbFrameTime = 0;
private final int climbFrameDelay = 120; // ms per frame

    private int x = 300;
    private int y = 100;
    private double vx = 0;
    private double vy = 0;
    private final double gravity = 0.4;

    private int stateTimer = 0;
    private int jitterTimer = 0;
    private int idleTicks = 0;

    private int limbSwing = 0;
    private boolean swingForward = true;
    private int ceilingDirection = 1;

    private int headRadius = 15;

    private final Random random = new Random();
    private final Timer loopTimer;

    // ====== MOUSE / DRAG ======
    private int lastMouseX = 0;
    private int lastMouseY = 0;

    // ====== LIMB HITBOXES ======
    private enum Limb {
        NONE,
        LEFT_ARM,
        RIGHT_ARM,
        LEFT_LEG,
        RIGHT_LEG,
        TORSO
    }

    private Limb grabbedLimb = Limb.NONE;

    private Rectangle leftArmHitbox = new Rectangle();
    private Rectangle rightArmHitbox = new Rectangle();
    private Rectangle leftLegHitbox = new Rectangle();
    private Rectangle rightLegHitbox = new Rectangle();
    private Rectangle torsoHitbox = new Rectangle();

    // ====== VISUAL ======
    private final Color bodyColor;

    // ====== BLOCK SYSTEM ======
    enum BlockType {
        OAK_PLANK,
        OAK_LOG,
        OAK_STAIRS,
        OAK_SLAB,
        OAK_FENCE,
        OAK_FENCE_GATE,
        OAK_LEAVES,
        SLIME
    }

    static class Block {
        int x, y, size;
        BlockType type;

        Block(int x, int y, int size, BlockType type) {
            this.x = x;
            this.y = y;
            this.size = size;
            this.type = type;
        }
    }

    private final List<Block> blocks = new ArrayList<>();
    private int buildStep = 0;
    private int buildTimer = 0;

    // ====== SOUND ENGINE ======
    private static class SoundChannel {
        private Clip clip;
        private final String name;

        SoundChannel(String name) {
            this.name = name;
        }

        private Clip loadClip(String path) {
            try {
                File f = new File(path);
                if (!f.exists()) return null;
                AudioInputStream ais = AudioSystem.getAudioInputStream(f);
                Clip c = AudioSystem.getClip();
                c.open(ais);
                return c;
            } catch (Exception ex) {
                System.err.println("[" + name + "] Failed to load sound: " + path + " -> " + ex.getMessage());
                return null;
            }
        }

        void playOnce(String path) {
            stop();
            clip = loadClip(path);
            if (clip != null) {
                clip.start();
            }
        }

        void loop(String path) {
            if (clip != null && clip.isActive()) return;
            stop();
            clip = loadClip(path);
            if (clip != null) {
                clip.loop(Clip.LOOP_CONTINUOUSLY);
            }
        }

        void stop() {
            if (clip != null) {
                clip.stop();
                clip.close();
                clip = null;
            }
        }
    }

    private final SoundChannel footstepChannel = new SoundChannel("footstep");
    private final SoundChannel impactChannel = new SoundChannel("impact");
    private final SoundChannel snoreChannel = new SoundChannel("snore");
    private final SoundChannel wooshChannel = new SoundChannel("woosh");

    private static final String SOUND_FOOTSTEP = "sounds/footstep.wav";
    private static final String SOUND_PUNCH    = "sounds/punch.wav";
    private static final String SOUND_SNORE    = "sounds/snore.wav";
    private static final String SOUND_WOOSH    = "sounds/woosh.wav";

    public StickmanGame() {
        Dimension ss = Toolkit.getDefaultToolkit().getScreenSize();
        this.screenWidth = ss.width;
        this.screenHeight = ss.height;
        this.baseGroundY = screenHeight - 60;
        this.rightWallX = screenWidth - 25;

        setSize(screenWidth, screenHeight);
        setLocation(0, 0);
        setAlwaysOnTop(true);
        setBackground(new Color(0, 0, 0, 0));

        float h = random.nextFloat();
        float s = 0.8f;
        float b = 0.95f;
        this.bodyColor = Color.getHSBColor(h, s, b);

        setupInteractionEngines();
        setupKeyboardControls();

        loopTimer = new Timer(16, this);
        loopTimer.start();
    }

    private void setupInteractionEngines() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                Point p = e.getPoint();

                if (leftArmHitbox.contains(p)) {
                    grabbedLimb = Limb.LEFT_ARM;
                } else if (rightArmHitbox.contains(p)) {
                    grabbedLimb = Limb.RIGHT_ARM;
                } else if (leftLegHitbox.contains(p)) {
                    grabbedLimb = Limb.LEFT_LEG;
                } else if (rightLegHitbox.contains(p)) {
                    grabbedLimb = Limb.RIGHT_LEG;
                } else if (torsoHitbox.contains(p)) {
                    grabbedLimb = Limb.TORSO;
                } else {
                    grabbedLimb = Limb.NONE;
                    return;
                }

                currentState = 5;
                lastMouseX = e.getX();
                lastMouseY = e.getY();
                vx = 0;
                vy = 0;
                idleTicks = 0;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (currentState == 5) {
                    grabbedLimb = Limb.NONE;
                    currentState = 4;
                }
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (grabbedLimb == Limb.NONE || currentState != 5) return;

                int mx = e.getX();
                int my = e.getY();

                vx = mx - lastMouseX;
                vy = my - lastMouseY;

                lastMouseX = mx;
                lastMouseY = my;

                switch (grabbedLimb) {
                    case LEFT_ARM:
                        x = mx + 15;
                        y = my + 20;
                        break;
                    case RIGHT_ARM:
                        x = mx - 15;
                        y = my + 20;
                        break;
                    case LEFT_LEG:
                        x = mx + 10;
                        y = my - 20;
                        break;
                    case RIGHT_LEG:
                        x = mx - 10;
                        y = my - 20;
                        break;
                    case TORSO:
                        x = mx;
                        y = my;
                        break;
                    default:
                        break;
                }

                x = Math.max(leftWallX, Math.min(x, rightWallX));
                y = Math.max(roofY + 40, Math.min(y, getCurrentGroundY()));

                repaint();
            }
        });
    }

    private void setupKeyboardControls() {
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int codeKey = e.getKeyCode();

                if (codeKey == KeyEvent.VK_D) {
                    SwingUtilities.invokeLater(() -> {
                        if (CLONES.size() >= MAX_CLONES) {
                            StickmanGame oldest = CLONES.remove(0);
                            oldest.dispose();
                        }
                        StickmanGame c = new StickmanGame();
                        c.x = x + 40;
                        c.y = y;
                        c.setVisible(true);
                        CLONES.add(c);
                    });
                }

                if (codeKey == KeyEvent.VK_C) {
                    for (StickmanGame c : CLONES) {
                        c.dispose();
                    }
                    CLONES.clear();
                }
            }
        });
        setFocusable(true);
        requestFocusInWindow();
    }

    @Override
    public void paint(Graphics g) {
        super.paint(g);
        Graphics2D g2d = (Graphics2D) g;

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        for (Block b : blocks) {
            drawBlock(g2d, b);
        }

        g2d.setColor(bodyColor);
        g2d.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        switch (currentState) {
            case 3:
                renderSittingFlat(g2d);
                break;
            case 6:
                renderWallClimb(g2d, true);
                break;
            case 7:
                renderWallClimb(g2d, false);
                break;
            case 8:
                renderCeilingMonkeybar(g2d);
                break;
            case 9:
                renderWindowInteraction(g2d);
                break;
            case 10:
                renderYayPose(g2d);
                break;
            case 11:
                renderSleepingState(g2d);
                break;
            case 12:
                renderBuildPose(g2d);
                break;
            default:
                renderStandardPoses(g2d);
                break;
        }
    }
    private void renderCeilingMonkeybar(Graphics2D g2d) {
    int neckY = roofY + 30;
    int crotchY = neckY + 40;

    // Head and torso hanging from ceiling
    g2d.drawOval(x - headRadius, roofY + 5, headRadius * 2, headRadius * 2);
    g2d.drawLine(x, neckY, x, crotchY);

    // Arms grabbing the ceiling bar
    int arm1X = x - 15 + limbSwing;
    int arm1Y = roofY;
    int arm2X = x + 15 - limbSwing;
    int arm2Y = roofY;
    g2d.drawLine(x, neckY, arm1X, arm1Y);
    g2d.drawLine(x, neckY, arm2X, arm2Y);

    // Legs swinging slightly
    int legLagX = ceilingDirection * 8;

    int lknX = x - 10;
    int lknY = crotchY + 12;
    int lftX = lknX - 5 - legLagX;
    int lftY = lknY + 12;
    g2d.drawLine(x, crotchY, lknX, lknY);
    g2d.drawLine(lknX, lknY, lftX, lftY);

    int rknX = x + 10;
    int rknY = crotchY + 12;
    int rftX = rknX + 5 - legLagX;
    int rftY = rknY + 12;
    g2d.drawLine(x, crotchY, rknX, rknY);
    g2d.drawLine(rknX, rknY, rftX, rftY);

    // Hitboxes
    int boxSize = 24;
    leftArmHitbox.setBounds(arm1X - boxSize / 2, arm1Y - boxSize / 2, boxSize, boxSize);
    rightArmHitbox.setBounds(arm2X - boxSize / 2, arm2Y - boxSize / 2, boxSize, boxSize);
    leftLegHitbox.setBounds(lftX - boxSize / 2, lftY - boxSize / 2, boxSize, boxSize);
    rightLegHitbox.setBounds(rftX - boxSize / 2, rftY - boxSize / 2, boxSize, boxSize);
    torsoHitbox.setBounds(x - 10, neckY, 20, crotchY - neckY);
}


    private void drawBlock(Graphics2D g2d, Block b) {
        int s = b.size;
        switch (b.type) {
            case OAK_PLANK:
                g2d.setColor(new Color(193, 154, 107));
                g2d.fillRect(b.x, b.y, s, s);
                g2d.setColor(new Color(160, 120, 80));
                g2d.drawRect(b.x, b.y, s, s);
                g2d.drawLine(b.x, b.y + s / 3, b.x + s, b.y + s / 3);
                g2d.drawLine(b.x, b.y + 2 * s / 3, b.x + s, b.y + 2 * s / 3);
                break;
            case OAK_LOG:
                g2d.setColor(new Color(120, 85, 60));
                g2d.fillRect(b.x, b.y, s, s);
                g2d.setColor(new Color(80, 55, 40));
                g2d.drawRect(b.x, b.y, s, s);
                g2d.drawLine(b.x + s / 3, b.y, b.x + s / 3, b.y + s);
                g2d.drawLine(b.x + 2 * s / 3, b.y, b.x + 2 * s / 3, b.y + s);
                break;
            case OAK_STAIRS:
                g2d.setColor(new Color(193, 154, 107));
                g2d.fillRect(b.x, b.y + s / 2, s, s / 2);
                g2d.fillRect(b.x, b.y + s / 4, 3 * s / 4, s / 4);
                g2d.fillRect(b.x, b.y, s / 2, s / 4);
                g2d.setColor(new Color(160, 120, 80));
                g2d.drawRect(b.x, b.y + s / 2, s, s / 2);
                break;
            case OAK_SLAB:
                g2d.setColor(new Color(193, 154, 107));
                g2d.fillRect(b.x, b.y + s / 2, s, s / 2);
                g2d.setColor(new Color(160, 120, 80));
                g2d.drawRect(b.x, b.y + s / 2, s, s / 2);
                break;
            case OAK_FENCE:
                g2d.setColor(new Color(160, 120, 80));
                g2d.fillRect(b.x + s / 4, b.y, s / 6, s);
                g2d.fillRect(b.x + s / 2, b.y, s / 6, s);
                g2d.fillRect(b.x, b.y + s / 3, s, s / 6);
                g2d.fillRect(b.x, b.y + 2 * s / 3, s, s / 6);
                break;
            case OAK_FENCE_GATE:
                g2d.setColor(new Color(160, 120, 80));
                g2d.fillRect(b.x + s / 4, b.y, s / 6, s);
                g2d.fillRect(b.x + s / 2, b.y, s / 6, s);
                g2d.drawRect(b.x, b.y + s / 3, s, s / 3);
                break;
            case OAK_LEAVES:
                g2d.setColor(new Color(60, 140, 60, 200));
                g2d.fillRoundRect(b.x, b.y, s, s, 10, 10);
                g2d.setColor(new Color(30, 90, 30));
                g2d.drawRoundRect(b.x, b.y, s, s, 10, 10);
                break;
            case SLIME:
                g2d.setColor(new Color(80, 255, 120, 180));
                g2d.fillRect(b.x, b.y, s, s);
                g2d.setColor(new Color(40, 180, 80));
                g2d.drawRect(b.x, b.y, s, s);
                break;
        }
    }

    private void renderStandardPoses(Graphics2D g2d) {
        int ny = y - 40;
        int cy = y;

        int headY = y - (headRadius * 2) - 40;
        g2d.drawOval(x - headRadius, headY, headRadius * 2, headRadius * 2);
        g2d.drawLine(x, ny, x, cy);

        int leftArmX, leftArmY, rightArmX, rightArmY;
        int leftKneeX, leftKneeY, rightKneeX, rightKneeY;

        if (currentState == 5 || currentState == 4) {
            int lagX = (int) Math.max(-10, Math.min(vx, 10));
            int lagY = (int) Math.max(-10, Math.min(vy, 10));

            leftArmX = x - 12;
            leftArmY = ny + 10;
            g2d.drawLine(x, ny + 5, leftArmX, leftArmY);
            g2d.drawLine(leftArmX, leftArmY, leftArmX - 10 - lagX, leftArmY - 5 - lagY);

            rightArmX = x + 12;
            rightArmY = ny + 10;
            g2d.drawLine(x, ny + 5, rightArmX, rightArmY);
            g2d.drawLine(rightArmX, rightArmY, rightArmX + 10 - lagX, rightArmY - 5 - lagY);

            leftKneeX = x - 10;
            leftKneeY = cy + 15;
            g2d.drawLine(x, cy, leftKneeX, leftKneeY);
            g2d.drawLine(leftKneeX, leftKneeY, leftKneeX - 8 - lagX, leftKneeY + 15 - lagY);

            rightKneeX = x + 10;
            rightKneeY = cy + 15;
            g2d.drawLine(x, cy, rightKneeX, rightKneeY);
            g2d.drawLine(rightKneeX, rightKneeY, rightKneeX + 8 - lagX, rightKneeY + 15 - lagY);
        } else if (currentState == 1 || currentState == 2) {
            leftArmX = x - limbSwing;
            leftArmY = ny + 25;
            rightArmX = x + limbSwing;
            rightArmY = ny + 25;
            g2d.drawLine(x, ny + 5, leftArmX, leftArmY);
            g2d.drawLine(x, ny + 5, rightArmX, rightArmY);

            leftKneeX = x - limbSwing;
            leftKneeY = cy + 40;
            rightKneeX = x + limbSwing;
            rightKneeY = cy + 40;
            g2d.drawLine(x, cy, leftKneeX, leftKneeY);
            g2d.drawLine(x, cy, rightKneeX, rightKneeY);
        } else {
            leftArmX = x - 12;
            leftArmY = ny + 25;
            rightArmX = x + 12;
            rightArmY = ny + 25;
            g2d.drawLine(x, ny + 5, leftArmX, leftArmY);
            g2d.drawLine(x, ny + 5, rightArmX, rightArmY);

            leftKneeX = x - 10;
            leftKneeY = cy + 40;
            rightKneeX = x + 10;
            rightKneeY = cy + 40;
            g2d.drawLine(x, cy, leftKneeX, leftKneeY);
            g2d.drawLine(x, cy, rightKneeX, rightKneeY);
        }

        int boxSize = 24;
        leftArmHitbox.setBounds(leftArmX - boxSize / 2, leftArmY - boxSize / 2, boxSize, boxSize);
        rightArmHitbox.setBounds(rightArmX - boxSize / 2, rightArmY - boxSize / 2, boxSize, boxSize);
        leftLegHitbox.setBounds(leftKneeX - boxSize / 2, leftKneeY - boxSize / 2, boxSize, boxSize);
        rightLegHitbox.setBounds(rightKneeX - boxSize / 2, rightKneeY - boxSize / 2, boxSize, boxSize);
        torsoHitbox.setBounds(x - 10, ny, 20, cy - ny);
    }

    private void renderSittingFlat(Graphics2D g2d) {
        int ny = getCurrentGroundY() - 35;
        g2d.drawOval(x - headRadius, ny - (headRadius * 2), headRadius * 2, headRadius * 2);
        g2d.drawLine(x, ny, x, getCurrentGroundY());
        g2d.drawLine(x, ny + 5, x - 15, getCurrentGroundY() - 15);
        g2d.drawLine(x, ny + 5, x + 15, getCurrentGroundY() - 15);
        g2d.drawLine(x, getCurrentGroundY(), x - 18, getCurrentGroundY() - 15);
        g2d.drawLine(x - 18, getCurrentGroundY() - 15, x - 30, getCurrentGroundY());
        g2d.drawLine(x, getCurrentGroundY(), x + 18, getCurrentGroundY() - 15);
        g2d.drawLine(x + 18, getCurrentGroundY() - 15, x + 30, getCurrentGroundY());

        int boxSize = 24;
        leftArmHitbox.setBounds(x - 20 - boxSize / 2, getCurrentGroundY() - 20 - boxSize / 2, boxSize, boxSize);
        rightArmHitbox.setBounds(x + 8 - boxSize / 2, getCurrentGroundY() - 20 - boxSize / 2, boxSize, boxSize);
        leftLegHitbox.setBounds(x - 25 - boxSize / 2, getCurrentGroundY() - 5 - boxSize / 2, boxSize, boxSize);
        rightLegHitbox.setBounds(x + 13 - boxSize / 2, getCurrentGroundY() - 5 - boxSize / 2, boxSize, boxSize);
        torsoHitbox.setBounds(x - 10, ny, 20, getCurrentGroundY() - ny);
    }

private void renderWallClimb(Graphics2D g2d, boolean left) {

    // === FRAME CYCLING ===
    long now = System.currentTimeMillis();
    if (now - lastClimbFrameTime > climbFrameDelay) {
        climbFrame = (climbFrame + 1) % 4; // 4 frames
        lastClimbFrameTime = now;
    }

    // === SHARED POSITIONING ===
    int wx = left ? leftWallX : rightWallX;
    int dir = left ? 1 : -1;

    int sx = wx + (dir * 22);
    int ny = y - 30;
    int cy = y + 15;

    // Limb trackers
    int leftArmY = ny;
    int rightArmY = ny;
    int leftLegY = cy;
    int rightLegY = cy;

    // === HEAD + TORSO (same for all frames) ===
    g2d.drawOval(sx - headRadius, ny - headRadius * 2, headRadius * 2, headRadius * 2);
    g2d.drawLine(sx, ny, sx, cy);

    // ============================================================
    // FRAME 0 — Right arm high, Left leg high
    // ============================================================
    if (climbFrame == 0) {

        // RIGHT ARM HIGH
        rightArmY = ny - 20;
        g2d.drawLine(sx, ny + 5, wx, rightArmY);

        // LEFT ARM LOW
        leftArmY = ny + 15;
        g2d.drawLine(sx, ny + 5, wx, leftArmY);

        // LEFT LEG HIGH
        leftLegY = cy - 2;
        int kneeLX = sx + (dir * 12);
        g2d.drawLine(sx, cy, kneeLX, cy + 4);
        g2d.drawLine(kneeLX, cy + 4, wx, leftLegY);

        // RIGHT LEG LOW
        rightLegY = cy + 22;
        int kneeRX = sx + (dir * 8);
        g2d.drawLine(sx, cy, kneeRX, cy + 12);
        g2d.drawLine(kneeRX, cy + 12, wx, rightLegY);
    }

    // ============================================================
    // FRAME 1 — Left arm high, Right leg high
    // ============================================================
    else if (climbFrame == 1) {

        // LEFT ARM HIGH
        leftArmY = ny - 20;
        g2d.drawLine(sx, ny + 5, wx, leftArmY);

        // RIGHT ARM LOW
        rightArmY = ny + 15;
        g2d.drawLine(sx, ny + 5, wx, rightArmY);

        // RIGHT LEG HIGH
        rightLegY = cy - 2;
        int kneeRX = sx + (dir * 12);
        g2d.drawLine(sx, cy, kneeRX, cy + 4);
        g2d.drawLine(kneeRX, cy + 4, wx, rightLegY);

        // LEFT LEG LOW
        leftLegY = cy + 22;
        int kneeLX = sx + (dir * 8);
        g2d.drawLine(sx, cy, kneeLX, cy + 12);
        g2d.drawLine(kneeLX, cy + 12, wx, leftLegY);
    }

    // ============================================================
    // FRAME 2 — Mid arms, Mid legs
    // ============================================================
    else if (climbFrame == 2) {

        // BOTH ARMS MID
        leftArmY = ny - 5;
        rightArmY = ny - 5;
        g2d.drawLine(sx, ny + 5, wx, leftArmY);
        g2d.drawLine(sx, ny + 5, wx, rightArmY);

        // BOTH LEGS MID
        leftLegY = cy + 10;
        rightLegY = cy + 10;

        int kneeLX = sx + (dir * 10);
        int kneeRX = sx + (dir * 10);

        g2d.drawLine(sx, cy, kneeLX, cy + 8);
        g2d.drawLine(kneeLX, cy + 8, wx, leftLegY);

        g2d.drawLine(sx, cy, kneeRX, cy + 8);
        g2d.drawLine(kneeRX, cy + 8, wx, rightLegY);
    }

    // ============================================================
    // FRAME 3 — Opposite mid transition
    // ============================================================
    else if (climbFrame == 3) {

        // LEFT ARM MID-HIGH
        leftArmY = ny - 12;
        g2d.drawLine(sx, ny + 5, wx, leftArmY);

        // RIGHT ARM MID-LOW
        rightArmY = ny + 5;
        g2d.drawLine(sx, ny + 5, wx, rightArmY);

        // RIGHT LEG MID-HIGH
        rightLegY = cy + 2;
        int kneeRX = sx + (dir * 10);
        g2d.drawLine(sx, cy, kneeRX, cy + 6);
        g2d.drawLine(kneeRX, cy + 6, wx, rightLegY);

        // LEFT LEG MID-LOW
        leftLegY = cy + 16;
        int kneeLX = sx + (dir * 10);
        g2d.drawLine(sx, cy, kneeLX, cy + 10);
        g2d.drawLine(kneeLX, cy + 10, wx, leftLegY);
    }

    // ============================================================
    // HITBOXES (shared)
    // ============================================================
    int box = 24;

    leftArmHitbox.setBounds(wx - box/2, leftArmY - box/2, box, box);
    rightArmHitbox.setBounds(wx - box/2, rightArmY - box/2, box, box);

    leftLegHitbox.setBounds(wx - box/2, leftLegY - box/2, box, box);
    rightLegHitbox.setBounds(wx - box/2, rightLegY - box/2, box, box);

    int torsoHeight = Math.max(1, cy - ny);
    torsoHitbox.setBounds(sx - 10, ny, 20, torsoHeight);
}



    private void renderWindowInteraction(Graphics2D g2d) {
        int ny = y - 40;
        int cy = y;

        g2d.drawOval(x - headRadius, y - (headRadius * 2) - 40, headRadius * 2, headRadius * 2);
        g2d.drawLine(x, ny, x, cy);

        int arm1X = x - 35;
        int arm1Y = ny + 5;
        int arm2X = x + 35;
        int arm2Y = ny + 5;
        g2d.drawLine(x, ny + 5, arm1X, arm1Y);
        g2d.drawLine(x, ny + 5, arm2X, arm2Y);

        int leg1X = x - 15;
        int leg1Y = cy + 40;
        int leg2X = x + 15;
        int leg2Y = cy + 40;
        g2d.drawLine(x, cy, leg1X, leg1Y);
        g2d.drawLine(x, cy, leg2X, leg2Y);

        int boxSize = 24;
        leftArmHitbox.setBounds(arm1X - boxSize / 2, arm1Y - boxSize / 2, boxSize, boxSize);
        rightArmHitbox.setBounds(arm2X - boxSize / 2, arm2Y - boxSize / 2, boxSize, boxSize);
        leftLegHitbox.setBounds(leg1X - boxSize / 2, leg1Y - boxSize / 2, boxSize, boxSize);
        rightLegHitbox.setBounds(leg2X - boxSize / 2, leg2Y - boxSize / 2, boxSize, boxSize);
        torsoHitbox.setBounds(x - 10, ny, 20, cy - ny);
    }

    private void renderYayPose(Graphics2D g2d) {
        int cy = y + ((limbSwing > 0) ? -12 : 0);
        int ny = cy - 40;

        g2d.drawOval(x - headRadius, cy - (headRadius * 2) - 40, headRadius * 2, headRadius * 2);
        g2d.drawLine(x, ny, x, cy);

        int arm1X = x - 25;
        int arm1Y = ny - 25;
        int arm2X = x + 25;
        int arm2Y = ny - 25;
        g2d.drawLine(x, ny + 5, arm1X, arm1Y);
        g2d.drawLine(x, ny + 5, arm2X, arm2Y);

        int leg1X = x - 18;
        int leg1Y = cy + 40;
        int leg2X = x + 18;
        int leg2Y = cy + 40;
        g2d.drawLine(x, cy, leg1X, leg1Y);
        g2d.drawLine(x, cy, leg2X, leg2Y);

        int boxSize = 24;
        leftArmHitbox.setBounds(arm1X - boxSize / 2, arm1Y - boxSize / 2, boxSize, boxSize);
        rightArmHitbox.setBounds(arm2X - boxSize / 2, arm2Y - boxSize / 2, boxSize, boxSize);
        leftLegHitbox.setBounds(leg1X - boxSize / 2, leg1Y - boxSize / 2, boxSize, boxSize);
        rightLegHitbox.setBounds(leg2X - boxSize / 2, leg2Y - boxSize / 2, boxSize, boxSize);
        torsoHitbox.setBounds(x - 10, ny, 20, cy - ny);
    }

    private void renderSleepingState(Graphics2D g2d) {
        int ground = getCurrentGroundY();
        int sleepY = ground - 5;

        g2d.drawOval(x - 35 - headRadius, sleepY - headRadius, headRadius * 2, headRadius * 2);
        g2d.drawLine(x - 35, sleepY, x + 15, sleepY);

        g2d.drawLine(x - 30, sleepY, x - 40, sleepY + 4);
        g2d.drawLine(x - 30, sleepY, x - 20, sleepY - 4);

        g2d.drawLine(x + 15, sleepY, x + 30, sleepY + 4);
        g2d.drawLine(x + 15, sleepY, x + 30, sleepY - 4);

        g2d.setFont(new Font("Arial", Font.BOLD, 12));
        String snore = (limbSwing > 4) ? "Zzz..." : "z...";
        g2d.drawString(snore, x - 35, sleepY - 25);

        int boxSize = 24;
        leftArmHitbox.setBounds(x - 45 - boxSize / 2, sleepY - 10 - boxSize / 2, boxSize, boxSize);
        rightArmHitbox.setBounds(x + 20 - boxSize / 2, sleepY - 10 - boxSize / 2, boxSize, boxSize);
        leftLegHitbox.setBounds(x - 10 - boxSize / 2, sleepY - 5 - boxSize / 2, boxSize, boxSize);
        rightLegHitbox.setBounds(x + 10 - boxSize / 2, sleepY - 5 - boxSize / 2, boxSize, boxSize);
        torsoHitbox.setBounds(x - 35, sleepY - 6, 50, 12);
    }

    private void renderBuildPose(Graphics2D g2d) {
        int ny = y - 40;
        int cy = y;

        g2d.drawOval(x - headRadius, y - (headRadius * 2) - 40, headRadius * 2, headRadius * 2);
        g2d.drawLine(x, ny, x, cy);

        int arm1X = x + 20;
        int arm1Y = ny + 10;
        g2d.drawLine(x, ny + 5, arm1X, arm1Y);
        g2d.drawLine(arm1X, arm1Y, arm1X + 10, arm1Y + 5);

        int arm2X = x - 15;
        int arm2Y = ny + 20;
        g2d.drawLine(x, ny + 5, arm2X, arm2Y);

        int leg1X = x - 10;
        int leg1Y = cy + 40;
        int leg2X = x + 10;
        int leg2Y = cy + 40;
        g2d.drawLine(x, cy, leg1X, leg1Y);
        g2d.drawLine(x, cy, leg2X, leg2Y);

        int boxSize = 24;
        leftArmHitbox.setBounds(arm2X - boxSize / 2, arm2Y - boxSize / 2, boxSize, boxSize);
        rightArmHitbox.setBounds(arm1X - boxSize / 2, arm1Y - boxSize / 2, boxSize, boxSize);
        leftLegHitbox.setBounds(leg1X - boxSize / 2, leg1Y - boxSize / 2, boxSize, boxSize);
        rightLegHitbox.setBounds(leg2X - boxSize / 2, leg2Y - boxSize / 2, boxSize, boxSize);
        torsoHitbox.setBounds(x - 10, ny, 20, cy - ny);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (!isActive()) {
            requestFocusInWindow();
        }

        previousState = currentState;

        if (currentState == 5) {
            calculateLimbOscillation();
            repaint();
            return;
        }

        if (currentState == 4) {
            executeTossPhysicsEngine();
            calculateLimbOscillation();
            handleSoundTransitions();
            repaint();
            return;
        }

        if (y < getCurrentGroundY() && currentState != 6 && currentState != 7 && currentState != 8 && currentState != 12) {
            currentState = 4;
        }

        if (currentState == 0) {
            idleTicks++;
            if (idleTicks >= 100 && random.nextInt(100) < 80) {
                startBuildSequence();
            } else if (idleTicks >= 300 && currentState != 12) {
                currentState = 11;
                stateTimer = random.nextInt(250) + 150;
                idleTicks = 0;
            }
        } else if (currentState != 11 && currentState != 12) {
            idleTicks = 0;
        }

        if (currentState != 6 && currentState != 7 && currentState != 8 && currentState != 11 && currentState != 12) {
            stateTimer--;
            if (stateTimer <= 0) {
                stateTimer = random.nextInt(150) + 80;
                int r = random.nextInt(100);

                if (r < 18) {
                    currentState = 10;
                } else if (r < 45) {
                    currentState = random.nextInt(3);
                } else if (r < 70) {
                    currentState = 3;
                } else {
                    currentState = 9;
                    jitterTimer = 0;
                }
            }
        }

        if (currentState == 12) {
            updateBuildSequence();
        } else {
            executeStateMovementLogic();
        }

        calculateLimbOscillation();
        handleSoundTransitions();
        repaint();
    }

    private int getCurrentGroundY() {
        int ground = baseGroundY;
        for (Block b : blocks) {
            if (x >= b.x && x <= b.x + b.size) {
                int top = b.y;
                if (top < ground && Math.abs(y - top) < 80) {
                    ground = top;
                }
            }
        }
        return ground;
    }

    private void executeTossPhysicsEngine() {
        x += (int) vx;
        y += (int) vy;
        vy += gravity;
        vx *= 0.98;

        boolean impacted = false;

        if (x <= leftWallX) {
            x = leftWallX;
            vx = -vx * 0.3;
            impacted = true;
        }

        if (x >= rightWallX) {
            x = rightWallX;
            vx = -vx * 0.3;
            impacted = true;
        }

        if (y <= roofY + 40) {
            y = roofY + 40;
            vy = -vy * 0.3;
            impacted = true;
        }

        int ground = getCurrentGroundY();
        Block slimeHit = getSlimeBlockUnderFeet();

        if (slimeHit != null && vy > 0 && y >= slimeHit.y - 40) {
            y = slimeHit.y - 40;
            vy = -vy * 1.2;
            impactChannel.playOnce(SOUND_PUNCH);
            wooshChannel.playOnce(SOUND_WOOSH);
            impacted = true;
        } else if (y >= ground) {
            y = ground;
            if (vy > 2) {
                impactChannel.playOnce(SOUND_PUNCH);
            }
            vy = 0;
            impacted = true;
            if (Math.abs(vx) < 1.5) {
                vx = 0;
                currentState = 0;
            }
        }

        if (impacted && currentState == 4 && Math.abs(vx) < 0.5 && Math.abs(vy) < 0.5) {
            currentState = 0;
        }
    }

    private Block getSlimeBlockUnderFeet() {
        for (Block b : blocks) {
            if (b.type == BlockType.SLIME) {
                if (x >= b.x && x <= b.x + b.size && y <= b.y && y >= b.y - 80) {
                    return b;
                }
            }
        }
        return null;
    }

    private void executeStateMovementLogic() {
        switch (currentState) {
            case 1:
                x -= 2;
                if (x <= leftWallX) {
                    x = leftWallX;
                    currentState = 6;
                }
                break;

            case 2:
                x += 2;
                if (x >= rightWallX) {
                    x = rightWallX;
                    currentState = 7;
                }
                break;

            case 6:
                y -= 2;
                x = leftWallX;
                if (y <= roofY + 45) {
                    y = roofY + 45;
                    x = leftWallX + 15;
                    currentState = 8;
                    ceilingDirection = 1;
                }
                break;

            case 7:
                y -= 2;
                x = rightWallX;
                if (y <= roofY + 45) {
                    y = roofY + 45;
                    x = rightWallX - 15;
                    currentState = 8;
                    ceilingDirection = -1;
                }
                break;

            case 8:
                y = roofY + 45;
                x += (ceilingDirection * 2);

                if (ceilingDirection == -1 && x <= leftWallX + 5) {
                    x = leftWallX;
                    currentState = 6;
                }

                if (ceilingDirection == 1 && x >= rightWallX - 5) {
                    x = rightWallX;
                    currentState = 7;
                }

                if (random.nextInt(400) == 7) {
                    currentState = 4;
                    vy = 1;
                }
                break;

            case 9:
                jitterTimer++;
                if (jitterTimer % 20 == 0) {
                    x += (random.nextBoolean() ? 4 : -4);
                }
                break;

            case 11:
                y = getCurrentGroundY();
                vx = 0;
                vy = 0;
                stateTimer--;
                if (stateTimer <= 0) {
                    currentState = 0;
                }
                break;

            default:
                break;
        }
    }

    private void calculateLimbOscillation() {
        boolean activeAnims =
                (currentState == 1 || currentState == 2 ||
                 currentState == 6 || currentState == 7 ||
                 currentState == 8 || currentState == 10 ||
                 currentState == 11 || currentState == 12);

        if (activeAnims) {
            limbSwing += swingForward ? 1 : -1;
            if (limbSwing >= 12) {
                swingForward = false;
            }
            if (limbSwing <= -12) {
                swingForward = true;
            }
        } else {
            if (limbSwing > 0) limbSwing--;
            if (limbSwing < 0) limbSwing++;
        }
    }

    private void handleSoundTransitions() {
        boolean wasWalking = (previousState == 1 || previousState == 2);
        boolean isWalking = (currentState == 1 || currentState == 2);

        if (!wasWalking && isWalking) {
            footstepChannel.loop(SOUND_FOOTSTEP);
        } else if (wasWalking && !isWalking) {
            footstepChannel.stop();
        }

        boolean wasSleeping = (previousState == 11);
        boolean isSleeping = (currentState == 11);

        if (!wasSleeping && isSleeping) {
            snoreChannel.loop(SOUND_SNORE);
        } else if (wasSleeping && !isSleeping) {
            snoreChannel.stop();
        }

        boolean wasExpressive = (previousState == 9 || previousState == 10);
        boolean isExpressive = (currentState == 9 || currentState == 10);

        if (!wasExpressive && isExpressive) {
            wooshChannel.playOnce(SOUND_WOOSH);
        }

        if (previousState != 12 && currentState == 12) {
            wooshChannel.playOnce(SOUND_WOOSH);
        }
    }

    private void startBuildSequence() {
        currentState = 12;
        buildStep = 0;
        buildTimer = 0;
    }

    private void updateBuildSequence() {

    // Pick build mode ONCE per build cycle
    if (buildStep == 0 && buildFix == 0) {
        buildMode = random.nextInt(7); // now 7 structures
        buildFix = 1;
    }

    buildTimer++;
    int size = 40;

    // Build relative to stickman
    int baseX = x - 200;
    int ground = baseGroundY;

    // Slow down building
    if (buildTimer % 8 != 0) return;

    switch (buildMode) {

        /* ============================
           0 — SLIME PAD
        ============================ */
        case 0:
            if (buildStep == 0) {
                blocks.add(new Block(baseX + 160, ground - size, size, BlockType.SLIME));
                blocks.add(new Block(baseX + 200, ground - size, size, BlockType.SLIME));
                blocks.add(new Block(baseX + 240, ground - size, size, BlockType.SLIME));
            }
            break;

        /* ============================
           1 — TREE
        ============================ */
        case 1:
            if (buildStep == 0) {
                for (int i = 0; i < 3; i++) {
                    blocks.add(new Block(baseX + 200, ground - size * (i + 1), size, BlockType.OAK_LOG));
                }
            } else if (buildStep == 1) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = 0; dy < 2; dy++) {
                        blocks.add(new Block(baseX + 200 + dx * size, ground - size * (4 + dy), size, BlockType.OAK_LEAVES));
                    }
                }
            }
            break;

        /* ============================
           2 — HOUSE + PORCH
        ============================ */
        case 2:
            if (buildStep == 0) {
                for (int i = 0; i < 5; i++) {
                    blocks.add(new Block(baseX + i * size, ground - size, size, BlockType.OAK_PLANK));
                }
            } else if (buildStep == 1) {
                for (int yRow = 2; yRow <= 4; yRow++) {
                    for (int i = 0; i < 5; i++) {
                        blocks.add(new Block(baseX + i * size, ground - size * yRow, size, BlockType.OAK_PLANK));
                    }
                }
            } else if (buildStep == 2) {
                blocks.add(new Block(baseX + size, ground - size * 5, size, BlockType.OAK_STAIRS));
                blocks.add(new Block(baseX + 2 * size, ground - size * 5, size, BlockType.OAK_STAIRS));
                blocks.add(new Block(baseX + 3 * size, ground - size * 5, size, BlockType.OAK_STAIRS));
            } else if (buildStep == 3) {
                blocks.add(new Block(baseX - size, ground - size, size, BlockType.OAK_SLAB));
                blocks.add(new Block(baseX - size, ground - size * 2, size, BlockType.OAK_FENCE));
            }
            break;

        /* ============================
           3 — HOUSE + CHIMNEY
        ============================ */
        case 3:
            if (buildStep == 0) {
                for (int i = 0; i < 4; i++) {
                    blocks.add(new Block(baseX + i * size, ground - size, size, BlockType.OAK_PLANK));
                }
            } else if (buildStep == 1) {
                for (int yRow = 2; yRow <= 4; yRow++) {
                    for (int i = 0; i < 4; i++) {
                        blocks.add(new Block(baseX + i * size, ground - size * yRow, size, BlockType.OAK_PLANK));
                    }
                }
            } else if (buildStep == 2) {
                blocks.add(new Block(baseX + 3 * size, ground - size * 5, size, BlockType.OAK_LOG));
                blocks.add(new Block(baseX + 3 * size, ground - size * 6, size, BlockType.OAK_LOG));
            }
            break;

        /* ============================
           4 — TOWER (NEW)
        ============================ */
        case 4:
            if (buildStep < 6) {
                blocks.add(new Block(baseX + 200, ground - size * (buildStep + 1), size, BlockType.OAK_PLANK));
            }
            break;

        /* ============================
           5 — OVERHANG CANOPY TREE (NEW)
        ============================ */
        case 5:
            if (buildStep == 0) {
                // trunk
                for (int i = 0; i < 4; i++) {
                    blocks.add(new Block(baseX + 200, ground - size * (i + 1), size, BlockType.OAK_LOG));
                }
            } else if (buildStep == 1) {
                // big canopy
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dy = 0; dy < 3; dy++) {
                        blocks.add(new Block(baseX + 200 + dx * size, ground - size * (5 + dy), size, BlockType.OAK_LEAVES));
                    }
                }
            }
            break;

        /* ============================
           6 — MINI BRIDGE (NEW)
        ============================ */
        case 6:
            if (buildStep == 0) {
                blocks.add(new Block(baseX + 120, ground - size, size, BlockType.OAK_PLANK));
                blocks.add(new Block(baseX + 160, ground - size, size, BlockType.OAK_PLANK));
                blocks.add(new Block(baseX + 200, ground - size, size, BlockType.OAK_PLANK));
            } else if (buildStep == 1) {
                blocks.add(new Block(baseX + 120, ground - size * 2, size, BlockType.OAK_FENCE));
                blocks.add(new Block(baseX + 200, ground - size * 2, size, BlockType.OAK_FENCE));
            }
            break;
    }

    // Move to next step
    buildStep++;

    // Finish building
    if (buildStep >= 5) {
        currentState = 0;   // go idle
        buildStep = 0;
        buildTimer = 0;
        buildFix = 0;       // allow new random build next time
        idleTicks = 0;      // reset idle timer so it doesn't immediately start building again
    }
}
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            StickmanGame c = new StickmanGame();
            c.setVisible(true);
        });
    }
}
