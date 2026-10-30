// A brief visible condensation jet clears the face, expands, then dissolves.
package com.dark.winter.client.weather;

import com.dark.winter.weather.WeatherValues;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleLimit;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/** A brief visible condensation jet clears the face, expands, then dissolves. */
public final class BreathParticle extends SingleQuadParticle {
    private static final ParticleLimit LIMIT = new ParticleLimit(120);
    private final float startSize;
    private final float opacity;
    private final float spin;
    private AbstractClientPlayer owner;
    private Vec3 previousOwnerPosition;
    private Vec3 carriedMotion = Vec3.ZERO;
    private float bodyFade = 1.0F;
    private boolean clearedBody;

    private BreathParticle(ClientLevel level, double x, double y, double z,
                           double dx, double dy, double dz, SpriteSet sprites) {
        super(level, x, y, z, sprites.first());
        setSprite(sprites.get(random));
        setSize(0.025F, 0.025F);
        startSize = 0.065F + random.nextFloat() * 0.025F;
        quadSize = startSize;
        opacity = 0.36F + random.nextFloat() * 0.10F;
        lifetime = 34 + random.nextInt(9);
        alpha = 0.0F;
        friction = 0.967F;
        gravity = -0.009F;
        xd = dx;
        yd = dy;
        zd = dz;
        spin = (random.nextFloat() - 0.5F) * 0.025F;
        roll = oRoll = random.nextFloat() * Mth.TWO_PI;
        setColor(0.93F, 0.96F, 1.0F);
    }

    /** The short jet shares the surrounding air carried by the moving person, then detaches. */
    void emittedBy(AbstractClientPlayer player) {
        owner = player;
        previousOwnerPosition = player.position();
    }

    @Override
    public void tick() {
        if (!WeatherClient.active()) {
            remove();
            return;
        }
        Vec3 ownerMotion = Vec3.ZERO;
        if (owner != null) {
            if (!owner.isAlive() || owner.isRemoved() || owner.level() != level
                    || (owner == Minecraft.getInstance().player
                        && Minecraft.getInstance().options.getCameraType().isFirstPerson())) {
                remove();
                return;
            }
            ownerMotion = owner.position().subtract(previousOwnerPosition);
            previousOwnerPosition = owner.position();
            if (ownerMotion.lengthSqr() > 4.0) {
                remove(); // A teleport must not drag an old exhale to the new position.
                return;
            }
            float release = Mth.clamp((age - 6.0F) / 10.0F, 0.0F, 1.0F);
            float carriage = 1.0F - release * release * (3.0F - 2.0F * release);
            // Actual displacement includes collision, server interpolation and terrain steps.
            // getDeltaMovement() here already contains ground drag and underestimates walking.
            carriedMotion = ownerMotion.scale(carriage);
        }
        WeatherValues weather = WeatherEffects.weather();
        // The initial jet needs to clear the head in the 0.8 m / 3 m shoulder view.
        // It then slows rapidly, so the visible cloud stays close to its short exhale.
        if (age >= 10) friction = 0.88F;
        // Strong outdoor gusts pull the exhale sideways; indoor air stays almost still.
        float wind = WeatherEffects.exposed(level, BlockPos.containing(x, y, z))
                ? weather.windSpeed() * WeatherEffects.localWindExposure() : 0.0F;
        xd += weather.windX() * wind * 0.0035;
        zd += weather.windZ() * wind * 0.0035;
        oRoll = roll;
        roll += spin;
        super.tick();
        carriedMotion = Vec3.ZERO;
        if (!level.getFluidState(BlockPos.containing(x, y, z)).isEmpty()) {
            remove();
        }
        if (!isAlive()) return;
        float progress = (float) age / lifetime;
        float envelope = Mth.clamp(age / 3.0F, 0.0F, 1.0F)
                * Mth.clamp((1.0F - progress) / 0.50F, 0.0F, 1.0F);
        double distance = Minecraft.getInstance().gameRenderer.mainCamera().position().distanceToSqr(x, y, z);
        alpha = opacity * envelope * Mth.clamp((float) (distance - 0.16) / 0.5F, 0.0F, 1.0F)
                * bodyClearance();
    }

    @Override
    public void move(double dx, double dy, double dz) {
        // Keep carriage separate from the jet: particle friction must not erase walking speed.
        super.move(dx + carriedMotion.x, dy + carriedMotion.y, dz + carriedMotion.z);
    }

    private float bodyClearance() {
        if (owner == null) return 1.0F;
        // Include the growing billboard, shoulder width and the small gait lean, not just its point.
        AABB body = owner.getBoundingBox().inflate(0.08, 0.04, 0.08);
        Vec3 position = new Vec3(x, y, z);
        Vec3 previous = new Vec3(xo, yo, zo).add(owner.position().subtract(owner.getPosition(0.0F)));
        float radius = getQuadSize(1.0F);
        // ParticleEngine queues a new particle for one tick before its first update. Check the
        // actual render endpoints too, so that first interpolated frame cannot start in the neck.
        double clearance = Math.sqrt(Math.min(body.distanceToSqr(position), body.distanceToSqr(previous))) - radius;
        float fade = Mth.clamp((float) clearance / 0.20F, 0.0F, 1.0F);
        if (clearedBody) {
            // Test in the owner's moving frame as well: a fast step cannot tunnel through the puff.
            if (body.inflate(radius).clip(previous, position).isPresent()) fade = 0.0F;
            bodyFade = Math.min(bodyFade, fade);
        } else if (fade >= 0.99F) {
            clearedBody = true;
        }
        if (fade <= 0.0F && (clearedBody || age > 4)) remove();
        return Math.min(bodyFade, fade);
    }

    @Override
    public float getQuadSize(float partialTicks) {
        float progress = Mth.clamp((age + partialTicks) / lifetime, 0.0F, 1.0F);
        return startSize + 0.27F * (1.0F - (1.0F - progress) * (1.0F - progress));
    }

    @Override
    public Layer getLayer() {
        return Layer.TRANSLUCENT;
    }

    @Override
    public Optional<ParticleLimit> getParticleLimit() {
        return Optional.of(LIMIT);
    }

    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double dx, double dy, double dz, RandomSource random) {
            return new BreathParticle(level, x, y, z, dx, dy, dz, sprites);
        }
    }
}
