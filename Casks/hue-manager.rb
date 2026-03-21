cask "hue-manager" do
  version "0.0.0-5f7cadb"
  sha256 "343cbdc5cc1c4869b6e32b5feafe0158c66eb4cb50642f8f6c0a6d46e0886fd0"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/378315061",
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
