cask "hue-manager" do
  version "0.0.0-da802d0"
  sha256 "baf4591c060ba1d8d9b4c1585c73f3116e221d0eb166afec33fb6ac9ded03933"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/376792129",
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
