package app.beat.alerts;

/** Wire format produced by AlertEngine before persistence. Pre-renders all UI copy. */
public record ComputedAlert(
    String alertType,
    String severity,
    int count,
    String badgeLabel,
    String cardTitle,
    String cardSubtitle,
    String cardActionLabel,
    String cardActionPath) {}
