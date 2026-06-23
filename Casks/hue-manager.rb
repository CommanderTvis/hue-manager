cask "hue-manager" do
  version "2026.06.23-a3a6117"
  sha256 "9c43ebe35e4250e80d96fa81cc537307b05f2d6751c305f7da6f24b1674bf73c"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/455758529",
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
