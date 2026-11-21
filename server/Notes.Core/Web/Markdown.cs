using System.Net;
using System.Text;
using System.Text.RegularExpressions;

namespace Notes.Core.Web;

/// <summary>
/// The basic Markdown of notes – the same subset as the clients (<c>core/model/Markdown.kt</c>); shared test cases in
/// <c>api/markdown-cases.json</c>.
/// <para>
/// Blocks are single lines (line breaks stay as written): headings <c>#</c>–<c>######</c>, list items <c>-</c>/<c>*</c>/<c>+</c>,
/// numbered items <c>1.</c>/<c>1)</c>, tasks <c>- [ ]</c>/<c>- [x]</c>, rules <c>---</c>, everything else is a plain line.
/// Inline: <c>**bold**</c>, <c>*italic*</c>/<c>_italic_</c>, <c>~~strike~~</c>, <c>`code`</c>, <c>[text](https://…)</c>,
/// bare http(s) links and backslash escapes.
/// </para>
/// All text is HTML-encoded; raw HTML is never passed through and links are limited to http(s) and mailto.
/// </summary>
public static partial class Markdown
{
    public abstract record Block;
    public sealed record Heading(int Level, IReadOnlyList<Inline> Inlines) : Block;
    public sealed record ListItem(int Indent, bool Ordered, string Marker, bool? Checked, IReadOnlyList<Inline> Inlines) : Block;
    public sealed record Rule : Block;
    public sealed record Line(IReadOnlyList<Inline> Inlines) : Block;

    public abstract record Inline;
    public sealed record Text(string Value) : Inline;
    public enum Style { Strong, Emphasis, Strike }
    public sealed record Styled(Style Style, IReadOnlyList<Inline> Children) : Inline;
    public sealed record Code(string Value) : Inline;
    public sealed record Link(string Url, IReadOnlyList<Inline> Children) : Inline;

    [GeneratedRegex(@"^(#{1,6})[ \t]+")] private static partial Regex HeadingRx();
    [GeneratedRegex(@"^([ \t]*)[-*+][ \t]+\[([ xX])\][ \t]+")] private static partial Regex TaskRx();
    [GeneratedRegex(@"^([ \t]*)[-*+][ \t]+")] private static partial Regex BulletRx();
    [GeneratedRegex(@"^([ \t]*)(\d{1,9}[.)])[ \t]+")] private static partial Regex OrderedRx();
    [GeneratedRegex(@"^[ \t]*(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$")] private static partial Regex RuleRx();

    private const string Escapable = "\\`*_~[]()#+-.!>";
    private const string Trailing = ".,;:!?)\"'";

    public static IReadOnlyList<Block> Parse(string text) =>
        text.Split('\n').Select(l => ParseLine(l.EndsWith('\r') ? l[..^1] : l)).ToList();

    private static Block ParseLine(string line)
    {
        if (HeadingRx().Match(line) is { Success: true } h)
            return new Heading(Math.Min(h.Groups[1].Length, 3), ParseInline(line[h.Length..]));
        if (RuleRx().IsMatch(line)) return new Rule();
        if (TaskRx().Match(line) is { Success: true } t)
            return new ListItem(IndentOf(t.Groups[1].Value), false, "", t.Groups[2].Value != " ", ParseInline(line[t.Length..]));
        if (BulletRx().Match(line) is { Success: true } b)
            return new ListItem(IndentOf(b.Groups[1].Value), false, "•", null, ParseInline(line[b.Length..]));
        if (OrderedRx().Match(line) is { Success: true } o)
            return new ListItem(IndentOf(o.Groups[1].Value), true, o.Groups[2].Value, null, ParseInline(line[o.Length..]));
        return new Line(ParseInline(line));
    }

    private static int IndentOf(string ws) => Math.Min(ws.Sum(c => c == '\t' ? 4 : 1) / 2, 6);

    public static IReadOnlyList<Inline> ParseInline(string s)
    {
        var output = new List<Inline>();
        var plain = new StringBuilder();
        var i = 0;
        void Flush()
        {
            if (plain.Length > 0) output.Add(new Text(plain.ToString()));
            plain.Clear();
        }

        while (i < s.Length)
        {
            var c = s[i];
            if (c == '\\' && i + 1 < s.Length && Escapable.Contains(s[i + 1]))
            {
                plain.Append(s[i + 1]);
                i += 2;
                continue;
            }
            var (node, end) = c switch
            {
                '`' => CodeAt(s, i),
                '*' => s.AsSpan(i).StartsWith("**") ? StyledAt(s, i, "**", Style.Strong) : StyledAt(s, i, "*", Style.Emphasis),
                '_' => s.AsSpan(i).StartsWith("__") ? StyledAt(s, i, "__", Style.Strong) : StyledAt(s, i, "_", Style.Emphasis),
                '~' => s.AsSpan(i).StartsWith("~~") ? StyledAt(s, i, "~~", Style.Strike) : (null, 0),
                '[' => LinkAt(s, i),
                'h' => AutolinkAt(s, i),
                _ => (null, 0),
            };
            if (node is not null)
            {
                Flush();
                output.Add(node);
                i = end;
                continue;
            }
            plain.Append(c);
            i++;
        }
        Flush();
        return output;
    }

    private static (Inline?, int) CodeAt(string s, int i)
    {
        var close = s.IndexOf('`', i + 1);
        return close < 0 || close == i + 1 ? (null, 0) : (new Code(s[(i + 1)..close]), close + 1);
    }

    private static (Inline?, int) StyledAt(string s, int i, string delim, Style style)
    {
        var n = delim.Length;
        var cs = i + n;
        if (cs >= s.Length || char.IsWhiteSpace(s[cs])) return (null, 0);
        var wordDelim = delim[0] == '_';
        if (wordDelim && i > 0 && char.IsLetterOrDigit(s[i - 1])) return (null, 0);
        for (var j = cs + 1; j + n <= s.Length; j++)
        {
            if (s.AsSpan(j).StartsWith(delim) && !char.IsWhiteSpace(s[j - 1]) &&
                // a single delimiter must not be half of a double one
                (n == 2 || ((j + 1 >= s.Length || s[j + 1] != delim[0]) && s[j - 1] != delim[0])) &&
                (!wordDelim || j + n >= s.Length || !char.IsLetterOrDigit(s[j + n])))
                return (new Styled(style, ParseInline(s[cs..j])), j + n);
        }
        return (null, 0);
    }

    private static (Inline?, int) LinkAt(string s, int i)
    {
        var mid = s.IndexOf("](", i + 1, StringComparison.Ordinal);
        if (mid < 0 || mid + 2 >= s.Length || mid == i + 1) return (null, 0);
        var close = s.IndexOf(')', mid + 2);
        if (close < 0) return (null, 0);
        var url = s[(mid + 2)..close].Trim();
        if (!IsSafeUrl(url) || url.Any(char.IsWhiteSpace)) return (null, 0);
        return (new Link(url, ParseInline(s[(i + 1)..mid])), close + 1);
    }

    private static (Inline?, int) AutolinkAt(string s, int i)
    {
        var rest = s.AsSpan(i);
        if (!rest.StartsWith("http://") && !rest.StartsWith("https://")) return (null, 0);
        if (i > 0 && char.IsLetterOrDigit(s[i - 1])) return (null, 0);
        var j = i;
        while (j < s.Length && !char.IsWhiteSpace(s[j])) j++;
        while (j > i && Trailing.Contains(s[j - 1])) j--;
        var url = s[i..j];
        if (url.Length <= url.IndexOf("//", StringComparison.Ordinal) + 2) return (null, 0);
        return (new Link(url, [new Text(url)]), j);
    }

    public static bool IsSafeUrl(string url) =>
        url.StartsWith("https://", StringComparison.Ordinal) || url.StartsWith("http://", StringComparison.Ordinal) ||
        url.StartsWith("mailto:", StringComparison.Ordinal);

    /// <summary>Text without markup (same output as the clients' <c>Markdown.toPlainText</c>).</summary>
    public static string ToPlainText(string text) => string.Join('\n', Parse(text).Select(b => b switch
    {
        Heading h => Plain(h.Inlines),
        ListItem l => new string(' ', 2 * l.Indent) + (l.Checked switch { null => l.Marker, true => "☑", false => "☐" }) + " " + Plain(l.Inlines),
        Rule => "—",
        Line l => Plain(l.Inlines),
        _ => "",
    }));

    private static string Plain(IEnumerable<Inline> inlines) => string.Concat(inlines.Select(n => n switch
    {
        Text t => t.Value,
        Code c => c.Value,
        Styled st => Plain(st.Children),
        Link l => Plain(l.Children),
        _ => "",
    }));

    /// <summary>One <c>div</c> per line (CSS classes <c>md-*</c>), so line breaks look exactly as typed.</summary>
    public static string ToHtml(string text)
    {
        var sb = new StringBuilder();
        foreach (var block in Parse(text))
        {
            switch (block)
            {
                case Heading h:
                    sb.Append("<div class=\"md-h").Append(h.Level).Append("\">");
                    AppendInlines(sb, h.Inlines);
                    sb.Append("</div>");
                    break;
                case ListItem l:
                    sb.Append("<div class=\"md-li").Append(l.Checked == true ? " md-done" : "").Append("\" style=\"--md-indent:").Append(l.Indent).Append("\">");
                    sb.Append("<span class=\"md-marker\">").Append(Enc(l.Checked switch { null => l.Marker, true => "☑", false => "☐" })).Append("</span><span>");
                    AppendInlines(sb, l.Inlines);
                    sb.Append("</span></div>");
                    break;
                case Rule:
                    sb.Append("<hr class=\"md-hr\" />");
                    break;
                case Line { Inlines.Count: 0 }:
                    sb.Append("<div class=\"md-blank\"></div>");
                    break;
                case Line l:
                    sb.Append("<div class=\"md-p\">");
                    AppendInlines(sb, l.Inlines);
                    sb.Append("</div>");
                    break;
            }
        }
        return sb.ToString();
    }

    private static void AppendInlines(StringBuilder sb, IEnumerable<Inline> inlines)
    {
        foreach (var n in inlines)
        {
            switch (n)
            {
                case Text t: sb.Append(Enc(t.Value)); break;
                case Code c: sb.Append("<code>").Append(Enc(c.Value)).Append("</code>"); break;
                case Styled st:
                    var tag = st.Style switch { Style.Strong => "strong", Style.Emphasis => "em", _ => "del" };
                    sb.Append('<').Append(tag).Append('>');
                    AppendInlines(sb, st.Children);
                    sb.Append("</").Append(tag).Append('>');
                    break;
                case Link l:
                    sb.Append("<a href=\"").Append(Enc(l.Url)).Append("\" target=\"_blank\" rel=\"noopener noreferrer\">");
                    AppendInlines(sb, l.Children);
                    sb.Append("</a>");
                    break;
            }
        }
    }

    private static string Enc(string s) => WebUtility.HtmlEncode(s);
}
