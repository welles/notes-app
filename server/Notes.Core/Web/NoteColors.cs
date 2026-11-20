namespace Notes.Core.Web;

/// <summary>
/// Shared colour palette (post-its and context accents). Names are stored, never hex values;
/// unknown names (from newer versions) are displayed with the fallback but kept unchanged.
/// </summary>
public static class NoteColors
{
    public static readonly IReadOnlyList<string> Names = ["yellow", "green", "blue", "pink", "purple", "orange", "gray"];

    private static readonly Dictionary<string, (string Background, string Accent, string Label)> Colors = new()
    {
        ["yellow"] = ("#fff3a3", "#e6c300", "Gelb"),
        ["green"] = ("#ccf0b3", "#43a047", "Grün"),
        ["blue"] = ("#c7defa", "#1e88e5", "Blau"),
        ["pink"] = ("#fbd0e3", "#d81b60", "Pink"),
        ["purple"] = ("#ddcdf7", "#7e57c2", "Lila"),
        ["orange"] = ("#fdd5a8", "#f57c00", "Orange"),
        ["gray"] = ("#e3e3e3", "#757575", "Grau"),
    };

    public static string Background(string? name, string fallback = "yellow") => Colors.GetValueOrDefault(name ?? "", Colors[fallback]).Background;
    public static string Accent(string? name, string fallback = "gray") => Colors.GetValueOrDefault(name ?? "", Colors[fallback]).Accent;
    public static string Label(string name) => Colors.TryGetValue(name, out var c) ? c.Label : name;

    /// <summary>Text on post-it backgrounds is always dark, also in dark mode, so the colours stay recognisable.</summary>
    public const string NoteText = "#202124";
}
