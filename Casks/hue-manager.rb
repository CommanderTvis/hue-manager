cask "hue-manager" do
  version "0.0.0-62a9bab"
  sha256 "7416c986bc1f859ea4c81eab3308c421f7385c4244ba1e2719e2ac69ea499fea"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/355311109",
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
