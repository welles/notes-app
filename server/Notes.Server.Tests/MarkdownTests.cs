using System.Text.Json.Nodes;
using Notes.Core.Web;

namespace Notes.Server.Tests;

public class MarkdownTests
{
    public static TheoryData<string, string> SharedCases()
    {
        var data = new TheoryData<string, string>();
        var doc = JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "markdown-cases.json")))!;
        foreach (var c in doc["cases"]!.AsArray())
            data.Add(c!["markdown"]!.GetValue<string>(), c["plain"]!.GetValue<string>());
        return data;
    }

    /// <summary>Same cases as the clients' MarkdownTest – both parsers must agree.</summary>
    [Theory]
    [MemberData(nameof(SharedCases))]
    public void Shared_cases_match_the_clients(string markdown, string plain) =>
        Assert.Equal(plain, Markdown.ToPlainText(markdown));

    [Fact]
    public void Html_is_line_based_and_encoded()
    {
        var html = Markdown.ToHtml("# Titel\n<b>x</b> **fett**\n\n- [x] [Link](https://a.org/?q=1&r=2)");
        Assert.Equal(
            "<div class=\"md-h1\">Titel</div>" +
            "<div class=\"md-p\">&lt;b&gt;x&lt;/b&gt; <strong>fett</strong></div>" +
            "<div class=\"md-blank\"></div>" +
            "<div class=\"md-li md-done\" style=\"--md-indent:0\"><span class=\"md-marker\">☑</span><span>" +
            "<a href=\"https://a.org/?q=1&amp;r=2\" target=\"_blank\" rel=\"noopener noreferrer\">Link</a></span></div>",
            html);
    }

    [Fact]
    public void Unsafe_links_stay_text()
    {
        Assert.DoesNotContain("<a", Markdown.ToHtml("[x](javascript:alert(1))"));
        Assert.DoesNotContain("<a", Markdown.ToHtml("[x](data:text/html,hi)"));
    }
}
