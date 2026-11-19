import unittest
from unittest.mock import patch

from topology import Topology


class PromotionGuardTest(unittest.TestCase):
    def test_no_promotion_before_fencing(self):
        topology = Topology("async")
        with patch.object(topology, "sql") as sql:
            with self.assertRaisesRegex(RuntimeError, "verified hard fence"):
                topology.promote()
            sql.assert_not_called()

    def test_previously_verified_fence_is_rechecked_before_promotion(self):
        topology = Topology("sync")
        topology._fence = {"verified_ns": 1}
        restarted = {"State": {"Running": True, "Status": "running"},
                     "HostConfig": {"RestartPolicy": {"Name": "no"}}}
        with patch.object(topology, "_inspect", return_value=restarted), patch.object(topology, "sql") as sql:
            with self.assertRaisesRegex(RuntimeError, "not hard-fenced"):
                topology.promote()
            sql.assert_not_called()
