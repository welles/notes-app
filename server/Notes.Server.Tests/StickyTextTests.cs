using Notes.Modules.Stickies;
using static Notes.Server.Tests.TestApp;

namespace Notes.Server.Tests;

public sealed class StickyTextTests
{
    private static readonly DateTime T0 = new(2026, 11, 20, 10, 0, 0, DateTimeKind.Utc);

    [Theory]
    [InlineData(null, "Text", "Text")]
    [InlineData("", "Text", "Text")]
    [InlineData("Titel", "Text", "# Titel\n\nText")]
    [InlineData(" Titel ", "", "# Titel")]
    public void Legacy_title_becomes_a_heading(string? title, string content, string expected) =>
        Assert.Equal(expected, StickyText.Combine(title, content));

    [Theory]
    [InlineData("# Titel\n\nText", "Titel", "Text")]
    [InlineData("# Titel", "Titel", "")]
    [InlineData("## Klein\nText", null, "## Klein\nText")]
    [InlineData("Text\n# Später", null, "Text\n# Später")]
    public void Leading_heading_can_be_split_off(string text, string? title, string rest) =>
        Assert.Equal((title, rest), StickyText.SplitHeading(text));

    [Theory]
    [InlineData("Text", "# Konflikt\n\nText")]
    [InlineData("# Einkauf\nMilch", "# Konflikt: Einkauf\n\nMilch")]
    public void Conflict_mark_goes_into_the_first_heading(string text, string expected) =>
        Assert.Equal(expected, StickyText.MarkConflict(text));

    [Fact]
    public async Task Editing_a_legacy_post_it_moves_its_title_into_the_text()
    {
        await using var app = await CreateAsync();
        var id = Guid.CreateVersion7();
        await app.PushOneAsync(NewSticky(id, T0, "Milch", "Einkauf"));
        var legacy = (await app.Stickies.GetAsync(id))!;
        Assert.Equal("# Einkauf\n\nMilch", StickyText.Of(legacy));

        await app.Stickies.UpdateAsync(id, legacy.Version, StickyPatch.Text(legacy, "# Einkauf\n\nMilch\nBrot"));

        var s = (await app.Stickies.GetAsync(id))!;
        Assert.Equal((null, "# Einkauf\n\nMilch\nBrot"), (s.Title, s.Content));
        Assert.Equal(new StickyPatch(), StickyPatch.Text(s, s.Content));
    }
}
