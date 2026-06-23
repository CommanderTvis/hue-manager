cask "hue-manager" do
  version "2026.06.23-ba7f3e5"
  sha256 "42c48db3d4be85261f7409ea99f0b7bd424e265b8c3aff7a2c6bc5c846f51db3"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/455810045",
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
