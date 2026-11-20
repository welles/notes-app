# syntax=docker/dockerfile:1

FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
ARG VERSION=0.0.0-dev
WORKDIR /src
COPY server/*.props server/global.json server/Notes.slnx ./server/
COPY server/Notes.Core/Notes.Core.csproj ./server/Notes.Core/
COPY server/Notes.Modules.Stickies/Notes.Modules.Stickies.csproj ./server/Notes.Modules.Stickies/
COPY server/Notes.Server/Notes.Server.csproj ./server/Notes.Server/
RUN dotnet restore server/Notes.Server/Notes.Server.csproj
COPY server/ ./server/
RUN dotnet publish server/Notes.Server/Notes.Server.csproj -c Release -o /app --no-restore \
      -p:Version=${VERSION} -p:OpenApiGenerateDocuments=false

FROM mcr.microsoft.com/dotnet/aspnet:10.0
WORKDIR /app
ENV ASPNETCORE_HTTP_PORTS=8080 \
    ConnectionStrings__Default="Data Source=/data/notes.db" \
    Notes__DataProtectionKeysPath=/data/keys \
    DOTNET_CLI_TELEMETRY_OPTOUT=1
RUN mkdir -p /data/keys && chown -R $APP_UID /data
COPY --from=build /app .
USER $APP_UID
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 CMD ["dotnet", "Notes.Server.dll", "--healthcheck"]
ENTRYPOINT ["dotnet", "Notes.Server.dll"]
