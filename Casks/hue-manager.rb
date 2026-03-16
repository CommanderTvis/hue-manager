cask "hue-manager" do
  version "0.0.0-8033227"
  sha256 "eb3a74dbed5dd4aec4bb94857357025c23f3c4716ff0656d90499eaf3918e5eb"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/374592406",
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
