namespace Notes.Core;

/// <summary>Global reference to any synchronized entity of any module. IDs are globally unique.</summary>
public sealed record EntityRef(string EntityType, Guid Id)
{
    public override string ToString() => $"{EntityType}/{Id}";
}
