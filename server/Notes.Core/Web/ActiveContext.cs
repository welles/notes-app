using Notes.Core.Contexts;

namespace Notes.Core.Web;

/// <summary>
/// The active context of this browser (per Blazor circuit). Device-local state – never synchronized.
/// Everything the web shows and captures refers to it.
/// </summary>
public sealed class ActiveContext
{
    public Context? Current { get; private set; }

    public Guid Id => Current?.Id ?? Context.DefaultId;

    public event Func<Task>? Changed;

    public async Task SetAsync(Context context)
    {
        var changed = Current?.Id != context.Id || Current?.Name != context.Name || Current?.Color != context.Color;
        Current = context;
        if (changed && Changed is { } handler)
            foreach (var h in handler.GetInvocationList().Cast<Func<Task>>()) await h();
    }
}
