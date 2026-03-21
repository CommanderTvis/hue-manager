cask "hue-manager" do
  version "2026.03.21-6144d82"
  sha256 "fbd939c00350eb58886aaee9dbe5926924c7159eba8b334682506f8ab6a4bd85"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/378318728",
      header: [
        "Authorization: token #{ENV.fetch("HOMEBREW_GITHUB_API_TOKEN", "")}",
        "Accept: application/octet-stream",
      ]
  name "Hue Manager"
  desc "Philips Hue lamp management desktop application"
  homepage "https://github.com/CommanderTvis/hue-manager"

  depends_on macos: ">= :ventura"

  app "Hue Manager.app"

  zap trash: [
    "~/Library/Preferences/io.github.commandertvis.huemanager.plist",
    "~/Library/Application Support/io.github.commandertvis.huemanager",
  ]
end
