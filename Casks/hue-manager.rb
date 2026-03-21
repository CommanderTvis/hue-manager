cask "hue-manager" do
  version "2026.03.21-b5c51e8"
  sha256 "7d27408841f2016e8b6ee94de491472526eb794a9bf088850b04da29fc012545"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/378325601",
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
