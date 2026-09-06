from app.tools.domain_utils import domains_share_registration, registrable_domain


def test_public_suffix_aware_registrable_domain():
    assert registrable_domain("mail.example.co.uk") == "example.co.uk"
    assert domains_share_registration("mail.example.co.uk", "reply.example.co.uk")


def test_private_suffixes_are_kept_separate():
    assert registrable_domain("sender.blogspot.com") == "sender.blogspot.com"
    assert not domains_share_registration("sender.blogspot.com", "attacker.blogspot.com")


def test_invalid_domain_has_no_registrable_domain():
    assert registrable_domain("localhost") is None
