cask "hue-manager" do
  version "2026.06.23-ccc1ff2"
  sha256 "92be0a60a8146fc71b866bf81aca1da29b212d9234641812fae8461caefe2740"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/455719210",
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
