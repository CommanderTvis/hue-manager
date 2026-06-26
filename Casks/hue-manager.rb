cask "hue-manager" do
  version "2026.06.26-21ca6c5"
  sha256 "4c5c9eb284a7fb30fc621ea609472272dfbd27f4a69893dc924b1774df171789"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/459077580",
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
