cask "hue-manager" do
  version "0.0.0-df5d55a"
  sha256 "301f6e562fc7baac192add4d37a4cfe699802aef51f44891254d82026c9285d3"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/354461266",
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
