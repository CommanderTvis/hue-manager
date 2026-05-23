cask "hue-manager" do
  version "2026.05.23-6f5e316"
  sha256 "691b0e2ea3843ecd5e977e5e3a7ad0537ee00084d4162b7299139bc91efb646e"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/428058106",
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
