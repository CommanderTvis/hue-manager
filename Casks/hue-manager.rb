cask "hue-manager" do
  version "2026.06.23-61edbb8"
  sha256 "1cec80e22dfbcd538fc22a9bf761b8599eb4623a4d3e782ceaedc7032564599e"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/455744477",
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
