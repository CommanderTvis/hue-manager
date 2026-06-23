cask "hue-manager" do
  version "2026.06.23-4e4e8fd"
  sha256 "516cfa07690602966e68786b86f7fe9ab8413564fe17222bf0d5d5f3cdfe2f2b"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/455742316",
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
