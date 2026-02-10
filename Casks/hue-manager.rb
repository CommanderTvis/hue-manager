cask "hue-manager" do
  version "0.0.0-a818401"
  sha256 "141c9024fffcae1144003ef63d7f4111b7d0caccc82149d5eb223ad23482d5e1"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/353362258",
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
