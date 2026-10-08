package dev.galactica.ship.collide;

import org.joml.Matrix3d;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Impulse response of one contact between two rigid bodies (pure Java): a normal impulse with restitution and a
 * Coulomb friction impulse at the contact point, both acting on linear and angular velocity through each body's mass
 * and world-frame inertia. Equal and opposite impulses conserve linear and angular momentum; with restitution ≤ 1 and
 * friction bounded by the cone the kinetic energy never grows.
 */
public final class RigidImpulse {
    private RigidImpulse() {}

    /**
     * A body's state for the solve. Units: kg, kg·m², m (blocks), m/s, rad/s; world frame. A mass of zero or less (or
     * infinite) is immovable.
     */
    public static final class Body {
        public final double mass;
        public final Matrix3d invInertia;
        public final Vector3d centre, velocity, omega;

        public Body(double mass, Matrix3d invInertia, Vector3dc centre, Vector3dc velocity, Vector3dc omega) {
            this.mass = mass;
            this.invInertia = new Matrix3d(invInertia);
            this.centre = new Vector3d(centre);
            this.velocity = new Vector3d(velocity);
            this.omega = new Vector3d(omega);
        }

        /** A body with principal moments {@code inertia} (body frame) turned by {@code attitude}. */
        public static Body of(double mass, Vector3dc inertia, Quaterniondc attitude, Vector3dc centre, Vector3dc velocity, Vector3dc omega) {
            return new Body(mass, worldInverseInertia(inertia, attitude), centre, velocity, omega);
        }

        public double invMass() {
            return mass > 0 && Double.isFinite(mass) ? 1 / mass : 0;
        }

        /** Velocity of the material point at {@code p}. */
        public Vector3d pointVelocity(Vector3dc p) {
            Vector3d r = new Vector3d(p).sub(centre);
            return new Vector3d(omega).cross(r).add(velocity);
        }

        public Vector3d momentum() {
            return new Vector3d(velocity).mul(mass);
        }

        /** Angular momentum about the world origin: r × p + I ω. */
        public Vector3d angularMomentum(Matrix3d inertia) {
            Vector3d l = new Vector3d(centre).cross(momentum());
            return l.add(inertia.transform(new Vector3d(omega)));
        }

        public double kineticEnergy(Matrix3d inertia) {
            return 0.5 * mass * velocity.lengthSquared() + 0.5 * omega.dot(inertia.transform(new Vector3d(omega)));
        }
    }

    /** R · diag(1/I) · Rᵀ. Zero moments (a point mass) give no rotational response. */
    public static Matrix3d worldInverseInertia(Vector3dc inertia, Quaterniondc attitude) {
        Matrix3d r = new Matrix3d().rotation(attitude);
        Matrix3d d = new Matrix3d().scaling(inv(inertia.x()), inv(inertia.y()), inv(inertia.z()));
        return new Matrix3d(r).mul(d).mul(new Matrix3d(r).transpose());
    }

    /** World inertia R · diag(I) · Rᵀ (for momentum and energy checks). */
    public static Matrix3d worldInertia(Vector3dc inertia, Quaterniondc attitude) {
        Matrix3d r = new Matrix3d().rotation(attitude);
        Matrix3d d = new Matrix3d().scaling(inertia.x(), inertia.y(), inertia.z());
        return new Matrix3d(r).mul(d).mul(new Matrix3d(r).transpose());
    }

    private static double inv(double v) {
        return v > 0 && Double.isFinite(v) ? 1 / v : 0;
    }

    /**
     * @param normalImpulse  N·s along the normal (≥ 0)
     * @param closingSpeed   normal approach speed before the solve, m/s (positive = approaching)
     * @param impulse        total impulse on A (on B it is the negative), N·s, world
     */
    public record Result(double normalImpulse, double closingSpeed, Vector3d impulse) {}

    /**
     * Applies the contact impulse to both bodies in place. {@code normal} points from B towards A. Returns null when
     * the bodies at the contact are already separating.
     */
    public static Result resolve(Body a, Body b, Vector3dc point, Vector3dc normal, double restitution, double friction) {
        Vector3d n = new Vector3d(normal).normalize();
        Vector3d ra = new Vector3d(point).sub(a.centre), rb = new Vector3d(point).sub(b.centre);
        Vector3d vRel = a.pointVelocity(point).sub(b.pointVelocity(point));
        double vn = vRel.dot(n);
        if (vn >= 0) return null;
        double jn = -(1 + restitution) * vn / effectiveInverseMass(a, b, ra, rb, n);
        Vector3d impulse = new Vector3d(n).mul(jn);
        apply(a, b, ra, rb, impulse);
        // Friction from the relative velocity left after the normal impulse, bounded by the cone.
        Vector3d vRel2 = a.pointVelocity(point).sub(b.pointVelocity(point));
        Vector3d vt = new Vector3d(vRel2).fma(-vRel2.dot(n), n);
        double vtLen = vt.length();
        if (vtLen > 1e-9 && friction > 0) {
            Vector3d t = new Vector3d(vt).div(vtLen);
            double jt = -vtLen / effectiveInverseMass(a, b, ra, rb, t);
            jt = Math.max(jt, -friction * jn);
            Vector3d ft = new Vector3d(t).mul(jt);
            apply(a, b, ra, rb, ft);
            impulse.add(ft);
        }
        return new Result(jn, -vn, impulse);
    }

    /** 1/mA + 1/mB + d·((IA⁻¹(rA×d))×rA) + d·((IB⁻¹(rB×d))×rB). */
    static double effectiveInverseMass(Body a, Body b, Vector3dc ra, Vector3dc rb, Vector3dc d) {
        Vector3d ta = a.invInertia.transform(new Vector3d(ra).cross(d)).cross(ra);
        Vector3d tb = b.invInertia.transform(new Vector3d(rb).cross(d)).cross(rb);
        return a.invMass() + b.invMass() + d.dot(ta) + d.dot(tb);
    }

    private static void apply(Body a, Body b, Vector3dc ra, Vector3dc rb, Vector3dc j) {
        a.velocity.fma(a.invMass(), j);
        b.velocity.fma(-b.invMass(), j);
        a.omega.add(a.invInertia.transform(new Vector3d(ra).cross(j)));
        b.omega.sub(b.invInertia.transform(new Vector3d(rb).cross(j)));
    }

    /**
     * Kinetic energy of the normal approach in the bodies' centre-of-mass frame, J: ½·μ·v², μ the reduced mass. What an
     * impact has to spend on crushing hulls.
     */
    public static double impactEnergy(double massA, double massB, double closingSpeed) {
        double mu = massA > 0 && massB > 0 ? massA * massB / (massA + massB) : Math.max(massA, massB);
        return 0.5 * mu * closingSpeed * closingSpeed;
    }

    /**
     * Hull points an impact removes from each hull: nothing below {@link #DAMAGE_SPEED} m/s of closing speed, then the
     * energy above that threshold at {@link #JOULES_PER_POINT} per point.
     */
    public static double hullDamage(double massA, double massB, double closingSpeed) {
        if (closingSpeed <= DAMAGE_SPEED) return 0;
        double e = impactEnergy(massA, massB, closingSpeed) - impactEnergy(massA, massB, DAMAGE_SPEED);
        return e / JOULES_PER_POINT;
    }

    /** Closing speed below which hulls take no damage (docking, a firm landing), m/s. */
    public static final double DAMAGE_SPEED = 3.0;
    /** Impact energy per hull point, J. */
    public static final double JOULES_PER_POINT = 5.0e5;
}
