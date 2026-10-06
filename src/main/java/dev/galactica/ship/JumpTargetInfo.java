package dev.galactica.ship;

/**
 * A hyperjump destination as the ship needs it: a name for messages and galactocentric parsecs. Deliberately
 * the same shape as the map's {@code nav.JumpTarget}, so the integrator's adapter is a field-by-field copy.
 */
public record JumpTargetInfo(String name, double xPc, double yPc, double zPc) {}
